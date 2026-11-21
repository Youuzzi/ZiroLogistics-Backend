# WMS Authentication and Session Design

**Status:** Proposed implementation baseline; no authentication endpoints are exposed by this document.  
**Target runtime:** WMS-only Spring Boot runtime (wms profile)  
**Current schema:** Frozen WMS V1; required persistence must be introduced through additive Flyway V2 or later migrations.  
**Scope:** Global identity authentication, tenant membership selection, access-token validation, refresh-token lifecycle, and request authorization.

## 1. Purpose and current state

The WMS already has a global identity model (users), tenant memberships, tenant roles, a global permission catalogue, and a database-backed membership/permission resolver. PR #17 implemented the resolver, but it does not authenticate credentials, issue tokens, or protect HTTP requests.

The legacy application has its own security model and must remain isolated. WMS authentication must not reuse legacy JWT subjects, legacy user lookup, or legacy role checks.

This document proposes a security design before implementation. It does not modify the frozen V1 schema or claim that the controls below are implemented.

## 2. Recommended architecture decisions

| Area | Recommended baseline | Reason |
|---|---|---|
| Password hashing | Argon2id via Spring Security password encoder; benchmark parameters on deployment hardware | Adaptive, memory-hard password hashing |
| Authentication framework | Spring Security AuthenticationManager / AuthenticationProvider and WMS-only SecurityFilterChain | Native Spring authentication lifecycle and SecurityContext |
| JWT support | Spring Security OAuth2 Resource Server + JOSE (Nimbus) | Standard bearer-token filter and strict JWT decoder integration |
| Access token | Asymmetric RS256; 10-minute lifetime; explicit issuer, audience, token type, key ID | Limits token exposure and supports key rotation |
| Refresh token | Opaque cryptographically random value; store only SHA-256 digest; rotate on every use | Enables server-side revocation and replay detection |
| Tenant selection | Explicit selection of one active membership after global credential authentication | A global user may belong to multiple tenants |
| Authorization | Resolve current membership and effective permissions from the database; deny by default | Avoids trusting stale token claims |
| Runtime | All WMS security beans and filters are restricted to wms profile | Prevents leakage into legacy runtime |

These are application design choices, not requirements imposed verbatim by Spring, OWASP, or the JWT RFC.

## 3. Password authentication

1. Accept normalized email and password over HTTPS only.
2. Normalize email consistently (trim and lowercase using a locale-independent rule) before lookup. Do not use a client-supplied user ID as proof of identity.
3. Verify the password using an adaptive password encoder. Preferred algorithm: Argon2id.
4. OWASP's published minimum Argon2id profile includes 19 MiB memory, 2 iterations, and parallelism 1. Benchmark a stronger profile on the target environment while keeping login latency and concurrent memory use within operational limits.
5. Never log, return, or persist raw passwords. Clear credentials after successful authentication.
6. Require users.status = ACTIVE. Suspended or pending-verification identities must not receive a tenant session. Email-verification policy must be implemented together with a real verification workflow; do not silently assume a non-null email verification timestamp exists in existing data.
7. Use the same external failure response for unknown email, wrong password, suspended user, and blocked/throttled attempt. Avoid account enumeration through response body, status, and avoidable timing differences.
8. Update last_login_at only after successful credential verification and emit a security audit event without recording credentials or tokens.

The WMS password_hash column is VARCHAR(255), sufficient for encoded adaptive hashes. Before implementation, verify whether any WMS rows already contain hashes and identify their format; do not assume legacy password hashes are compatible.

## 4. Tenant membership selection flow

A user is authenticated globally first. A tenant membership is a separate authorization choice.

Recommended two-step flow:

1. Credential login: POST /api/wms/auth/login verifies email/password and returns a short-lived, single-use membership-selection ticket plus the user's active membership choices. The ticket is random, opaque, bound server-side to the authenticated user, stored only as a digest, and expires after approximately 2 minutes.
2. Membership selection: POST /api/wms/auth/select-membership consumes the ticket and receives one membershipPublicId. The server resolves that membership using the authenticated user identity and WmsMembershipAccessService. A client-supplied tenant ID is never accepted as the authorization source.
3. If valid, create a server-side authentication session bound to that user and membership, then issue access and refresh credentials.
4. A user with one active membership follows the same flow; the client may select it automatically, but the server still validates it.
5. The selection ticket is single-use and short-lived. Repeated or expired tickets are rejected. The response must not reveal whether an arbitrary membership identifier belongs to another user.

The selection ticket and persistent session require additive persistence. They must not be simulated with process-local maps because that breaks restart and multi-instance behavior.

## 5. Access-token profile

Use an asymmetric signature (recommended algorithm: RS256) with a key ID (kid) in the protected JOSE header. Private signing keys must be injected from a secret-management mechanism or protected deployment secret; never commit them or place production private keys in application properties.

Required claims:

| Claim | Meaning |
|---|---|
| iss | Exact configured WMS token issuer |
| aud | Exact WMS API audience |
| sub | Global users.public_id |
| sid | Public identifier of the persisted authentication session |
| mid | Selected tenant_memberships.public_id |
| jti | Unique token identifier |
| iat | Issued-at timestamp |
| nbf | Not-before timestamp |
| exp | Expiration timestamp |
| token_use | Literal token purpose, e.g. access |

The decoder must validate signature, an explicit algorithm allow-list, issuer, audience, expiry, not-before, and token purpose. Reject missing or malformed required claims. Apply a small, explicit clock-skew allowance.

Do not put roles, permission codes, tenant numeric IDs, or other authorization decisions in the access token. The token's sub and mid are identifiers to be checked against current server-side state, not proof that the membership remains active.

Access-token lifetime baseline: 10 minutes. This is a proposed operational default, to be reviewed against client experience and risk profile.

## 6. Server-side session and refresh-token lifecycle

WMS V1 has no session, refresh-token, or revocation persistence. A secure refresh-token implementation therefore requires an additive migration before refresh endpoints are enabled.

Proposed persistence responsibilities:

- Login challenge: stores the user-bound selection-ticket digest, expiry, consumed timestamp, and attempt metadata.
- Authentication session: binds one global user to one selected membership; tracks active/revoked/expired state, creation, last use, absolute expiry, revocation time, and reason.
- Refresh-token history: stores a digest for each issued refresh token, its session, expiry, consumed timestamp, and replacement reference. Keep consumed-token history long enough to detect replay.

Refresh-token rules:

1. Generate at least 256 bits of cryptographically secure randomness. Return the raw token once; persist only its SHA-256 digest.
2. Bind each refresh token to exactly one server-side session, user, and selected membership.
3. Rotate on every successful refresh. The previous token becomes unusable and the replacement is persisted.
4. If a previously consumed token is presented again, treat it as replay and revoke the entire token family/session. Implement this so revocation is committed even though the refresh request returns an authentication failure; do not throw inside a transaction in a way that rolls back revocation.
5. Apply both an absolute session lifetime and an inactivity lifetime. Proposed initial defaults: 30-day absolute lifetime and 7-day inactivity lifetime; tune before production.
6. Logout revokes the server-side session and refresh-token family. The request filter checks session state, so revocation can take effect on the next request rather than waiting for access-token expiry.
7. Password change/reset, user suspension, membership suspension/removal, and tenant suspension revoke affected sessions. The existing membership resolver already rejects inactive user, membership, or tenant state; session revocation is an additional control.
8. Refresh tokens should be transported in a Secure, HttpOnly cookie with an appropriate SameSite policy and CSRF protection where cookie-based cross-site requests are possible. Never put refresh tokens in URLs or browser local storage.

Concurrency must be addressed with row locking or an equivalent atomic compare-and-swap so two simultaneous refresh requests cannot both rotate the same token successfully.

## 7. Request authentication and authorization pipeline

For every protected WMS request:

1. Spring Security bearer-token filter extracts the access token from the Authorization header.
2. JWT decoder validates cryptography and all required claims.
3. Resolve sid and require an active, unexpired server-side session bound to token sub and mid.
4. Call the current database-backed membership resolver using authenticated user public ID and selected membership public ID.
5. Build an immutable WMS principal and request-scoped tenant context from the resolved database result.
6. Enforce required permission codes and object ownership for the operation. Every protected operation is deny-by-default.
7. Clear the SecurityContext at request completion.

Never derive tenant context from a request header, body, URL parameter, or unvalidated JWT claim. A tenant identifier supplied by the client may be used as a resource selector only after it is checked against the authenticated membership context.

The WMS filter chain must be stateless for access-token authentication and must not enable form login or HTTP Basic. Cookie-based refresh endpoints need explicit CSRF handling. CORS must use an explicit frontend origin allow-list, not wildcard origins with credentials.

## 8. Login throttling and abuse controls

Do not ship a login endpoint with only an in-memory limiter. It is lost on restart and inconsistent across application instances.

Before public login is enabled, provide both:
- edge/gateway rate limiting by source network; and
- shared application-level throttling keyed by a keyed digest of normalized account identity plus source-network fingerprint.

Avoid permanent account lockouts that let an attacker deny service to a victim. Use bounded windows, progressive delay or temporary blocks, monitoring, and a documented reset/retention strategy. Do not store raw passwords, raw selection tickets, or raw refresh tokens in rate-limit records or logs.

The current repository has no shared rate-limiter service configured. This is a deployment prerequisite or a separately designed persistence/infrastructure slice, not something to fake with a local map.

## 9. Additive schema work required

Do not edit WMS V1 or the frozen source DDL to add authentication state. Before implementing the full flow, design a new Flyway migration (V2 or later) for:

- one-time login selection challenges;
- authentication sessions;
- refresh-token rotation history.

The migration must include tenant-consistency and ownership constraints where applicable, uniqueness for token digests, status checks, indexes for expiry/revocation cleanup, and foreign keys to existing users and tenant memberships. It must be tested only against disposable MySQL 8.4 Testcontainers databases.

A durable/shared login rate limiter must also be selected before exposing login publicly. Whether its state belongs in MySQL or an external shared store is a separate decision based on deployment topology and expected traffic.

## 10. Implementation sequence

1. Verify current WMS runtime and test isolation; keep all new security beans profile-scoped.
2. Add and test Argon2id password encoding, including wrong-password, unknown-user, suspended-user, and hash-format behavior.
3. Design and add additive auth persistence migration with MySQL 8.4 Testcontainers coverage.
4. Implement one-time login challenge and explicit membership selection.
5. Implement session issuance and short-lived access JWT validation using Spring Security JOSE/Resource Server.
6. Implement refresh rotation, concurrent refresh protection, replay detection, and logout/revocation.
7. Add WMS-only SecurityFilterChain, immutable principal, tenant context, and per-request membership/session revalidation.
8. Add method/endpoint authorization and tenant-scoped object ownership checks.
9. Add shared login throttling, audit events, and security observability.
10. Expose endpoints only after the full security integration test suite passes.

Do not implement all endpoints in one change. Deliver tested vertical slices and keep each PR small enough to audit.

## 11. Required verification

At minimum, integration tests must prove:

- password hashes are adaptive and raw passwords never appear in persistence or logs;
- unknown email, wrong password, and inactive identity have indistinguishable public failure responses;
- an expired, consumed, or wrong-user selection ticket cannot create a session;
- a user cannot select another user's membership;
- a membership from another tenant cannot be substituted into an existing session;
- expired, malformed, wrong-issuer, wrong-audience, wrong-algorithm, wrong-purpose, or tampered JWTs are rejected;
- a session revoked by logout/password reset is rejected on the next protected request;
- suspended user, membership, or tenant loses access on the next request;
- permission changes are reflected without trusting stale permission claims;
- refresh rotation allows one winner under concurrency and detects replay;
- replay revokes the session/token family durably;
- legacy runtime does not load WMS security beans or WMS repositories;
- tests use disposable MySQL 8.4 Testcontainers and never connect to developer/production databases.

A green unit test alone is not enough to claim HTTP authentication is secure. CI must run the integration suite against the exact PR head.

## 12. References

- Spring Security authentication architecture: https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html
- Spring Security 6.5 dependency and module guidance: https://docs.spring.io/spring-security/reference/6.5/getting-spring-security.html
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- RFC 7519 (JSON Web Token): https://www.rfc-editor.org/rfc/rfc7519.html
- RFC 9700 (OAuth 2.0 Security Best Current Practice): https://www.rfc-editor.org/rfc/rfc9700.html

# WMS Authentication and Token Architecture

**Status:** Proposed design; no authentication endpoint is enabled by this document.  
**Runtime:** WMS-only runtime (`wms` profile)  
**Schema:** Frozen V1 remains unchanged. Any persistence extension requires a separately reviewed additive Flyway migration.  
**Decision scope:** Authentication flow, tenant selection, access-token validation, password verification, throttling, revocation boundaries.

## 1. Current verified baseline

The WMS runtime now has:
- global user, tenant, membership, tenant role, and permission persistence;
- a database-backed `WmsMembershipAccessService` that binds the selected membership to the already authenticated global user;
- effective permissions resolved from current active database assignments;
- no WMS login/register/tenant-selection/refresh/logout endpoints and no WMS HTTP security filter chain.

The repository has Spring Security, JJWT 0.11.5 (retained for the legacy runtime), and the Spring OAuth2 Resource Server JWT support used by WMS. The WMS implementation must not reuse the legacy application's authentication principal, JWT subject semantics, filter chain, or role model without a separate compatibility review.

## 2. Security invariants

1. Authentication proves a global user identity; it does not select a tenant by itself.
2. A tenant membership public ID is a selector, never a credential.
3. The authenticated global user is the only trusted identity input to membership resolution.
4. Tenant ID and effective permissions are derived from current persisted state.
5. Never accept client-provided tenant IDs, role names, or permission lists as authorization facts.
6. Deny by default. Every protected operation must enforce its required permission and tenant-scoped object ownership.
7. Passwords are verified using adaptive password hashing; never plaintext, reversible encryption, or a fast general-purpose hash.
8. Authentication errors must not reveal whether an email exists, a password is wrong, or an account is suspended.
9. Authentication throttling must be shared/durable in a multi-instance deployment; a process-local counter is not a production control.
10. Do not issue refresh tokens until rotation/replay detection and durable session persistence exist.

## 3. Proposed two-stage authentication flow

### Stage A — credential authentication

Proposed endpoint: `POST /api/wms/v1/auth/login`.

- Accept normalized email and password only.
- Find the global user by normalized email.
- Verify the password using the configured `PasswordEncoder`.
- Reject non-ACTIVE users with the same externally visible authentication failure as invalid credentials.
- Apply account-key and source-key throttling before expensive password verification, with safe handling for unknown accounts.
- On success, issue a short-lived, tenant-neutral **tenant-selection token**. It identifies the global user only and is not accepted by ordinary WMS business APIs.
- Return the active memberships available to that user, with opaque membership public IDs and safe display metadata.

The tenant-selection token is not a refresh token and must have a distinct token type/profile and audience from a tenant-bound access token.

### Stage B — explicit tenant selection

Proposed endpoint: `POST /api/wms/v1/auth/tenant-selection`.

- Require a valid tenant-selection token.
- Accept one `membershipPublicId`.
- Call `WmsMembershipAccessService.resolve(authenticatedUserPublicId, membershipPublicId)`.
- Reject absent, suspended, removed, or cross-user memberships.
- Issue a short-lived tenant-bound access token only after successful database resolution.
- Do not copy role or permission lists into the token.

The selection token is intentionally short-lived. Unless a durable one-time token/session store is added, it is reusable until expiry; the API must not claim one-time use.

### Stage C — protected WMS request

- Validate signature, allowed algorithm, issuer, audience, expiry, not-before, and the expected access-token type/profile.
- Read the global user subject and selected membership public ID only from the validated token.
- Resolve the membership and effective permissions from the database on every protected request (or introduce an explicitly designed, bounded cache with invalidation before doing otherwise).
- Build the request's WMS principal from the returned `WmsMembershipAccess`.
- Enforce permission and tenant ownership at the service boundary; controllers are not the only security boundary.
- Clear the request security context at request completion.

This keeps user, tenant, membership, and role suspension effective on the next request, subject to database availability and transaction consistency.

## 4. Proposed token profile

Use separate token profiles for tenant selection and tenant-bound access. The exact issuer, audience values, signing key source, key rotation process, and lifetimes must be deployment configuration, not source-code constants.

| Claim / property | Tenant-selection token | Tenant-bound access token |
|---|---|---|
| `iss` | Required, configured issuer | Required, configured issuer |
| `sub` | Global user's opaque public ID | Global user's opaque public ID |
| `aud` | WMS authentication/selection API | WMS API audience |
| `iat`, `nbf`, `exp` | Required | Required |
| `jti` | Required unique token ID | Required unique token ID |
| `token_use` | `tenant_selection` | `access` |
| `mid` | Must be absent | Selected membership public ID |
| Roles / permissions | Must be absent | Must be absent |

Proposed initial lifetimes for evaluation, not standards requirements:
- tenant-selection token: 5 minutes;
- tenant-bound access token: 10 minutes.

Use an asymmetric signing key held outside the repository and injected through deployment secret management. Include a key identifier (`kid`) so verification keys can be rotated. Pin the accepted signing algorithm; never trust the token header to choose an arbitrary algorithm. A key rotation runbook and overlap window are required before production.

The WMS should use Spring Security's OAuth2 Resource Server JWT validation support where it fits the self-issued token arrangement, rather than adding a second independent JWT parsing path. Keep legacy JJWT behavior isolated until separately migrated.

## 5. Password storage and existing hashes

OWASP currently recommends Argon2id as the preferred password hashing choice, with a baseline configuration of at least 19 MiB memory, 2 iterations, and parallelism 1. The final cost must be benchmarked on the deployment hardware and under expected concurrent login load.

Before selecting the WMS encoder:
- inspect the format and provenance of any existing `users.password_hash` values without exposing hashes in logs;
- do not assume that existing rows were created by the legacy application or use a particular encoder;
- use an explicit, version-identifiable hash format;
- support a controlled migration path (for example, rehash after successful verification only when the old format is positively identified);
- never silently treat unknown or malformed hashes as plaintext.

If Argon2id support requires an additional provider dependency, add it explicitly and verify dependency/security compatibility. Do not change the legacy authentication encoder as part of WMS work.

## 6. Throttling, sessions, refresh, and revocation

The frozen WMS V1 schema does not provide dedicated durable login-attempt, refresh-token, or session-revocation persistence.

Therefore:
- do not use an in-memory map or local Caffeine cache as the sole production login throttle;
- do not issue refresh tokens in the first authentication slice;
- do not claim that client-side logout revokes a signed access token;
- client logout without a server-side session is token disposal only;
- short access-token expiry bounds the remaining validity of a stolen token;
- user, tenant, and membership status changes are checked through database resolution on each protected request.

Before production rollout, choose one of:
1. a shared external rate-limit store (for example Redis) with atomic counters and a documented outage policy; or
2. an additive, concurrency-safe database-backed throttle design.

If refresh tokens or immediate per-device logout/revocation are required, add a separate additive schema slice for durable auth sessions. Store only a cryptographic hash of opaque refresh-token secrets; support rotation, replay detection, expiry, revocation, and family-level revocation. Never edit the frozen V1 migration.

Password changes and account-wide logout also need explicit semantics. Without a session/version/revocation mechanism, already-issued access tokens remain usable until expiry, even though status changes are rechecked.

## 7. HTTP security and error behavior

- WMS gets its own `SecurityFilterChain`, scoped to the `wms` profile and WMS URL space.
- Legacy security configuration remains isolated.
- Public endpoints are explicitly allow-listed; all other WMS endpoints require authentication.
- Authentication failure returns a generic 401 response.
- Authenticated but insufficient permission returns 403.
- Invalid or unavailable tenant membership must not fall back to another tenant.
- CORS must use explicit configured origins; no wildcard credentialed CORS.
- Stateless access-token authentication must not rely on server-side HTTP sessions.
- Validate request DTOs and apply payload/rate limits to authentication endpoints.
- Do not log passwords, raw access/refresh tokens, Authorization headers, or full credential request bodies.

## 8. Recommended implementation sequence

1. Inspect WMS user entity/repository and confirm email normalization, hash provenance, nullable constraints, and schema column sizes.
2. Add password encoder configuration and focused unit tests; do not expose an endpoint yet.
3. Add token properties validation and signing/verification services with key rotation support and tests for invalid signature, wrong issuer/audience/type, expiry, not-before, and algorithm confusion.
4. Add WMS-only authentication principal and tenant context integration around `WmsMembershipAccessService`.
5. Add the WMS-only `SecurityFilterChain` and request authorization tests.
6. Add credential login and tenant selection endpoints only after shared/durable throttling and audit decisions are implemented.
7. Decide whether a V2 additive migration is required for sessions, refresh rotation, login throttling, or revocation before production rollout.
8. Run integration tests only against disposable MySQL 8.4 Testcontainers databases.

No production login endpoint should be enabled until the full HTTP security and abuse-resistance test suite passes.

## 9. Implemented security foundation (code status)

The following foundation slices have been merged:
- WMS-only Argon2id `PasswordEncoder` configuration with a 19 MiB / 2-iteration / parallelism-1 baseline and focused matching/profile-isolation tests.
- WMS email lookup normalization using trim and `Locale.ROOT` lowercase, centralized through `WmsUserLookupService`. Existing database values are not rewritten.
- WMS JWT key configuration is being implemented separately from the HTTP security chain. It uses Spring OAuth2 Resource Server/Nimbus, RS256, an RSA key pair supplied by deployment environment, separate tenant-selection and access decoders, and validation for issuer, timestamp, audience, token-use, key ID, and membership-claim profile.

The WMS JWT configuration is disabled by default, including when the WMS profile starts. A deployment must explicitly set `WMS_JWT_ENABLED=true` before the JWT beans are created. Only then are these deployment variables required:
- `WMS_JWT_ISSUER`
- `WMS_JWT_SELECTION_AUDIENCE`
- `WMS_JWT_ACCESS_AUDIENCE`
- `WMS_JWT_KEY_ID`
- `WMS_JWT_PUBLIC_KEY_PEM`
- `WMS_JWT_PRIVATE_KEY_PEM`

The private key must be provisioned through secret management and must never be committed to source control. RSA keys below 2048 bits or mismatched public/private key pairs are rejected. This initial configuration supports one active key ID; multi-key rotation and overlap are not implemented yet. No JWT signing endpoint, authentication endpoint, or `SecurityFilterChain` is enabled by this foundation.

## 10. References

These sources inform the design. They do not mandate the application's exact endpoint names, token lifetimes, or schema.

- Spring Security 6.5, OAuth 2.0 Resource Server JWT: https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html
- Spring Security, Servlet authentication architecture: https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- RFC 7519, JSON Web Token (JWT): https://www.rfc-editor.org/rfc/rfc7519.html
- RFC 9700, Best Current Practice for OAuth 2.0 Security (January 2025): https://www.rfc-editor.org/rfc/rfc9700.html

# WMS Tenant Context and Authorization Resolution

**Status:** Implemented foundation; authentication/token issuance and HTTP enforcement remain out of scope.  
**Runtime:** WMS-only runtime (wms profile)  
**Schema:** Frozen WMS V1; no schema changes in this slice.

## 1. Purpose

A global user may hold memberships in more than one tenant. A verified identity is therefore not, by itself, a tenant authorization context. The application must resolve one explicitly selected membership against current persisted state before a request can operate within a tenant.

This slice implements that database-backed resolution. It does not authenticate credentials, parse JWTs, issue tokens, expose endpoints, or install an HTTP security filter chain.

## 2. Trust boundaries

1. Authentication must establish the global user identity independently.
2. The client may select a membership using its opaque tenant_memberships.public_id.
3. The server resolves the selected membership using both the authenticated user's users.public_id and the selected membership public ID.
4. The database query accepts the membership only when the user, membership, and tenant are all ACTIVE.
5. Effective permissions are resolved from active tenant roles assigned to that membership and the global permission catalogue.
6. Tenant and membership numeric IDs returned by the database are internal server-side context. A client-supplied numeric tenant ID is never an authorization decision.

The selected membership identifier is a selector, not a credential. Knowing another user's membership public ID must not grant access because the lookup is bound to the already authenticated user public ID.

## 3. Effective permission rules

A permission is effective only when all of the following are true in the same database read:

- the authenticated global user exists and has status ACTIVE;
- the selected membership belongs to that user and has status ACTIVE;
- the membership's tenant has status ACTIVE;
- the membership-role assignment is scoped to that membership's tenant;
- the assigned role belongs to that same tenant and has status ACTIVE;
- the role-permission assignment points to a permission in the global catalogue.

Inactive roles do not contribute permissions. A valid active membership with no active role assignments remains a valid tenant context with an empty permission set. Authorization checks must deny by default when the required permission is absent.

The resolver returns an immutable WmsMembershipAccess value with the internal user, tenant, and membership IDs, public identifiers, and a set of effective permission codes.

## 4. Request lifecycle integration (next security slice)

The intended request pipeline is:

1. Validate the access token's cryptographic integrity and required claims.
2. Validate issuer, audience, expiry, not-before, and token type/profile.
3. Read the authenticated user subject and selected membership reference from validated claims.
4. Call WmsMembershipAccessService.resolve(...) against current database state.
5. Build the authenticated WMS principal / tenant context from the returned value.
6. Enforce permission codes and tenant-scoped object ownership for every protected operation.
7. Clear request security context at request completion.

The WMS tenant context must be derived from the database-verified membership, not directly from a request header, path parameter, or unverified JWT claim. Role and permission lists are not trusted from client-controlled request data.

## 5. Token and session decisions still pending

The frozen V1 schema has no dedicated refresh-token/session-revocation or login-attempt persistence tables. This slice therefore does not invent persistence in application memory or claim immediate revocation support.

Before exposing login or refresh endpoints, a separate design/implementation must decide:

- access-token lifetime, signing algorithm/key management, issuer, audience, token type, and claim profile;
- whether access tokens are short-lived and whether a persisted session/refresh-token table is required;
- refresh-token rotation and replay detection if refresh tokens are issued;
- logout, password-change, user-suspension, membership-suspension, and tenant-suspension revocation semantics;
- account-based and source-based login throttling with a durable/shared backing store;
- password-hash encoder and migration policy for existing password_hash values;
- generic authentication failure responses to reduce account enumeration;
- audit events for login, tenant selection, membership/role changes, and revocation.

No login, registration, tenant-selection, refresh, or logout API is exposed by this change.

## 6. Implementation boundaries

- WmsMembershipAccessRepository performs a single native read query that joins the global identity, membership, tenant, role assignments, roles, role-permission assignments, and permission catalogue.
- WmsMembershipAccessService rejects missing/blank identifiers and denies when no active membership row is found.
- WmsMembershipAccess is immutable and defensively copies its permission set.
- No schema migration is added.
- No legacy package is scanned or reused by the WMS runtime.
- No developer or production database is accessed by tests; integration coverage uses disposable MySQL 8.4 Testcontainers.

## 7. Security and verification references

These references inform the design; they do not prescribe the application's exact architecture.

- Spring Security documents the authenticated principal and authorities in SecurityContext, and the filter-based authentication lifecycle: https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html
- OWASP Authorization Cheat Sheet recommends deny-by-default and validating permissions on every request: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP Password Storage Cheat Sheet recommends adaptive password hashing such as Argon2id, bcrypt, or PBKDF2 rather than fast hashes: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet covers generic authentication responses and login throttling: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- RFC 7519 defines registered JWT claims including issuer, subject, audience, expiry, not-before, issued-at, and JWT ID: https://www.rfc-editor.org/rfc/rfc7519.html
- RFC 9700 is the OAuth 2.0 Security Best Current Practice and requires refresh-token rotation or sender-constraining for public clients when refresh tokens are issued: https://www.rfc-editor.org/rfc/rfc9700.html

## 8. Verification scope

The MySQL 8.4 Testcontainers integration test verifies:

- one global user can hold memberships in two tenants;
- a membership public ID cannot be resolved under a different user identity;
- the same permission granted in tenant A does not leak into tenant B;
- an inactive role contributes no effective permissions;
- suspended membership, user, or tenant causes membership resolution to be denied.

A green CI run on the exact PR head is required before merge. This test validates membership and permission resolution only; it is not proof that HTTP requests are authenticated or protected yet.

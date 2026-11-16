# WMS Authentication and Account Lifecycle HTTP API Contract

Status: Implemented as opt-in WMS HTTP adapters. The endpoints remain disabled unless both `wms.security.jwt.enabled=true` and `wms.security.http.auth-endpoints-enabled=true` are explicitly configured; see the deployment gates below.

## Principles

- WMS endpoints live under `/api/wms/v1` and are implemented in WMS-only packages scanned by `WmsRuntimeApplication`.
- No legacy controller or legacy authentication service is reused.
- Public routes are an explicit allowlist. Every route not listed below requires an authenticated WMS access token or is denied.
- Passwords and raw action tokens are submitted to API endpoints only in request bodies over TLS. Email action links carry tokens only in the URL fragment (never the query string or request path); the frontend must read the fragment and POST the token in the request body. Tokens must never appear in server access logs, traces, metrics, or exception messages.
- All account-recovery request responses are generic to prevent account enumeration.
- JSON errors use stable machine-readable codes but generic public messages.
- Controllers are thin transport adapters; application services own policy and transactions.

## Public authentication routes

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/wms/v1/auth/credentials` | Verify global user credentials and return tenant-selection token plus selectable memberships |
| POST | `/api/wms/v1/auth/tenant-selection` | Exchange a tenant-selection token and selected membership public ID for an access token |
| POST | `/api/wms/v1/auth/password-reset-requests` | Request password reset; always return generic accepted response |
| POST | `/api/wms/v1/auth/password-resets` | Consume one-time reset token and set new password |
| POST | `/api/wms/v1/auth/email-verification-requests` | Request verification email; always return generic accepted response |
| POST | `/api/wms/v1/auth/email-verifications` | Consume one-time verification token |
| POST | `/api/wms/v1/invitations/acceptances` | Accept invitation for a new global user and establish membership |

## Authenticated routes

| Method | Route | Required identity |
|---|---|---|
| POST | `/api/wms/v1/auth/password-changes` | Access token subject; current password required |
| POST | `/api/wms/v1/invitations/existing-account-acceptances` | Access token subject must match the invited email |

Invitation issuance is tenant-scoped and requires the authenticated membership to be active and hold the explicit `WMS_IDENTITY_MEMBERSHIP_INVITE` permission. The tenant ID is derived from the authenticated principal; it is never trusted from the request body. Existing global users accept an invitation through `POST /api/wms/v1/invitations/existing-account-acceptances`; that route is authenticated and the service verifies the invitation email matches the authenticated global user.

## Request/response rules

### Credentials

Input: normalized email and password. Socket peer address is supplied by the servlet container; X-Forwarded-For is considered only when the direct peer is in the deployment's explicitly configured trusted proxy CIDRs.

Success: short-lived tenant-selection token and memberships the user can actively select. Do not return permissions in this token.

Failure: generic authentication failure for unknown user, wrong password, inactive user, or throttled request. Throttle storage/policy unavailability fails closed.

### Tenant selection

Input: tenant-selection bearer token and membership public ID. The user identity is taken from the validated selection token. Membership and current database authorization are re-resolved before issuing the access token.

Success: short-lived access token containing only subject, membership reference, issuer/audience/time claims, token use, and token ID. Permissions remain database-resolved.

### Invitation and password lifecycle

- Invitation issuance requires `WMS_IDENTITY_MEMBERSHIP_INVITE` on the current active membership.
- New users are created only on successful invitation acceptance.
- Existing global users retain their password and profile during invitation acceptance.
- Password setup/reset/verification use one-time, purpose-bound tokens; token consumption and the associated state change share one transaction.
- Password changes require the current password and authenticated global user identity.
- Reset and verification request endpoints return the same public response for existing and unknown addresses.

## Security filter-chain requirements

1. Keep JWT-disabled mode deny-all.
2. In JWT-enabled mode, permit only the exact public POST routes above.
3. Require authentication for invitation issuance, existing-account invitation acceptance, password changes, and all future protected routes.
4. Use a separate tenant-selection token decoder for tenant selection. Do not accept an access token as a selection token or vice versa.
5. Do not globally permit `/api/**`, `/auth/**`, actuator endpoints, or arbitrary OPTIONS/GET paths.
6. Apply request-size limits and Bean Validation to all DTOs.
7. Never log request bodies for credential, invitation, password, or token endpoints.
8. Configure CORS origins explicitly at deployment; do not use wildcard origins with credentials.
9. Authentication endpoints must not expose internal throttle state, user existence, SQL errors, or token lifecycle details.

## Deployment gates

HTTP identity endpoints remain disabled unless both `wms.security.jwt.enabled=true` and `wms.security.http.auth-endpoints-enabled=true` are explicitly set. Credential login additionally requires all of the following configuration:
- WMS profile and WMS-only datasource;
- WMS JWT signing/verification key material and issuer/audiences;
- reviewed login throttle policy, dedicated Base64 HMAC key of at least 256 bits, and explicit key version;
- MySQL throttle storage enabled and migrations applied;
- trusted proxy CIDRs appropriate to the actual deployment topology;
- identity email outbox encryption key ring;
- if email delivery is enabled, SMTP host/port/username/password, verified sender address, HTTPS public action base URL, and the SMTP delivery worker configuration described below.

No default thresholds, proxy CIDRs, JWT secrets, email keys, or CORS origins are invented by application code. Missing required configuration must fail closed.

## Verification requirements

Use MockMvc/Spring Security tests for exact route authorization and DTO validation, plus disposable MySQL 8.4 Testcontainers for application flows. Cover:
- JWT disabled denies every WMS route;
- JWT enabled permits only the listed public POST routes anonymously;
- protected routes reject missing/invalid tokens;
- selection decoder rejects access tokens and vice versa;
- cross-user membership selection is rejected;
- generic reset/verification responses prevent account enumeration;
- invitation issuance requires the DB-resolved permission and tenant context;
- X-Forwarded-For spoofing from an untrusted peer does not alter the source throttle identity;
- throttle backend outage fails closed;
- secrets and request bodies are absent from logs;
- existing legacy HTTP security behavior remains unchanged.

## Explicit non-goals

- No public registration.
- No refresh token or logout revocation claim without a separately designed durable session lifecycle.
- No permissions embedded in JWT claims.
- No reliance on frontend route guards for authorization.
- No direct access to user-owned database instances during tests.


## Encrypted identity-email delivery worker

The SMTP delivery worker is opt-in and remains inactive unless
`wms.identity.email.delivery.enabled=true`. When enabled, startup requires
SMTP credentials, a valid sender address, and an HTTPS public action base URL.
Local HTTP is accepted only for localhost development. SMTP authentication and
mandatory STARTTLS are enabled; SMTP debug logging is disabled.

The worker claims one row at a time from the encrypted outbox using the existing
MySQL `FOR UPDATE SKIP LOCKED` adapter, decrypts only in memory, validates the
payload purpose and recipient against the outbox envelope, and sends one of the
three purpose-specific messages (invitation, email verification, password reset).
Raw action tokens are placed only in the action link fragment (for example, `#token=...`), never in a request path or query string. The frontend must extract the fragment and submit the token in the POST body; fragments are not transmitted in HTTP requests.
After successful SMTP submission the row is marked `SENT`. Failures receive an
opaque `DELIVERY_FAILED` code and bounded exponential retry; exhausted or
expired messages move to `DEAD_LETTER`. Expired processing leases are recovered
and the adapter's attempt-count fencing prevents stale workers from changing
reclaimed rows.

Delivery is at-least-once: if an SMTP server accepts a message but the database
connection fails before `SENT` is persisted, a later retry can produce a
duplicate email. SMTP itself does not provide a portable exactly-once guarantee.
Operational deployments should use a dedicated mailbox/provider, alert on
dead-letter growth, and protect the SMTP credentials and AES key ring through
secret management.

Required deployment environment variables when enabled:
- `WMS_EMAIL_DELIVERY_ENABLED=true`
- `WMS_SMTP_HOST`, `WMS_SMTP_PORT`, `WMS_SMTP_USERNAME`, `WMS_SMTP_PASSWORD`
- `WMS_EMAIL_FROM` (verified sender address)
- `WMS_PUBLIC_ACTION_BASE_URL` (HTTPS frontend origin)
- Existing `WMS_EMAIL_OUTBOX_ENCRYPTION_ENABLED=true`, current key version, and AES-256 key ring

Optional policy variables include `WMS_EMAIL_POLL_INTERVAL_MS`,
`WMS_EMAIL_MAX_MESSAGES_PER_POLL`, `WMS_EMAIL_MAX_ATTEMPTS`,
`WMS_EMAIL_RETRY_BASE_DELAY`, `WMS_EMAIL_RETRY_MAX_DELAY`,
`WMS_EMAIL_PROCESSING_LEASE`, SMTP timeout variables, and the three
`WMS_EMAIL_*_PATH` frontend route overrides. Default frontend paths are
`/accept-invitation`, `/verify-email`, and `/reset-password`; the frontend
must implement matching routes before real users receive these links.

# Platform Administration and Operations

**Status:** Target architecture approved for implementation planning; not implemented by this document.  
**Scope:** Global control-plane identity, platform authorization, tenant lifecycle administration, privileged support access, and platform audit.  
**Runtime baseline:** WMS Spring Boot runtime, MySQL 8.4, Spring Security, RS256 JWT, Flyway.  
**Database boundary:** Additive WMS migrations only. Do not modify the frozen schema reference or legacy database.

## 1. Executive decision

ZiroLogistics WMS must operate with two distinct security planes:

1. **Platform control plane** — identities employed or trusted by the SaaS operator (ZiroCraft Studio) to administer the WMS service and manage tenant lifecycle.
2. **Tenant data plane** — customer identities that access warehouse operations through an explicitly selected tenant membership.

A platform operator is not a tenant owner, and a tenant owner must never become a platform administrator by virtue of a tenant role. Neither plane may silently inherit the other's authority.

The current repository implements tenant-scoped membership authorization and a command-only tenant lifecycle foundation. It does **not** implement a persisted platform-operator identity, platform roles/permissions, platform authentication, or an authenticated platform HTTP boundary. The bootstrap implementation creates a tenant owner only. All platform capabilities described below are target design, not claims about current runtime behavior.

## 2. Current implementation baseline

The following facts are verified in the current source:

- `WmsSecurityConfiguration` installs a WMS servlet security chain matching `/**`. JWT is disabled by default and the disabled chain denies every request. When enabled, the chain authenticates bearer tokens and only explicitly configured authentication endpoints are public.
- `WmsJwtTokenService` issues a five-minute tenant-selection token and a ten-minute tenant-bound access token. Access tokens carry the selected membership reference and credential version; tenant permissions are deliberately resolved from persisted database state rather than copied into token claims.
- `WmsMembershipAccessService` resolves the authenticated global user and selected membership together, then derives effective permissions from persisted tenant role assignments.
- `WmsPlatformBootstrapService` creates a tenant, global user, active membership, `TENANT_OWNER` role, two permission grants, and a completion marker. Its class contract explicitly says it creates a tenant owner, not a global superuser.
- `WmsTenantLifecycleService` supports suspend, reactivate, and close transitions with an operation reference, reason, and audit event. `WmsTenantLifecycleCommandRunner` is enabled only in an explicitly invoked non-web command mode. The supplied `operatorReference` is currently a validated audit label, not a reference to an authenticated platform identity.
- Tenant lifecycle audit events currently record actor type `PLATFORM_OPERATOR` and a string actor reference. This is not proof of operator authentication or authorization.
- `application-wms.properties` has one WMS JWT issuer, tenant-selection audience, access audience, and key-material configuration. There is no separate platform token audience or platform security chain.

These existing controls are useful foundations, but they must not be mistaken for a complete SaaS control plane.

## 3. Security-plane boundaries

| Concern | Platform control plane | Tenant data plane |
|---|---|---|
| Principal | Platform operator identity | Global user plus selected tenant membership |
| Authority source | Platform role and permission grants | Tenant role and permission grants |
| Token profile | Platform-specific issuer/audience/token use | Existing WMS tenant-selection and access token profiles |
| Route namespace | `/api/platform/v1/**` | `/api/wms/v1/**` |
| Data scope | Explicit platform resources and permitted tenant metadata | Exactly one resolved tenant context per request |
| Audit actor | Persisted platform operator ID | Persisted tenant membership/user context |
| Default | Deny | Deny |

### Mandatory invariants

1. A tenant JWT is never accepted by a platform endpoint.
2. A platform JWT is never accepted by a tenant endpoint.
3. Platform roles are never stored as tenant roles and tenant roles are never interpreted as platform roles.
4. No request-supplied tenant ID, operator reference, email, role name, or permission list is accepted as proof of authority.
5. Platform access to a tenant's operational data is not implied by the ability to suspend, reactivate, or close that tenant.
6. All authorization decisions are server-side, deny-by-default, and evaluated on every request.
7. A platform administrator has no unrestricted “read or mutate every tenant record” bypass.
8. Background jobs and command runners use explicit service identities or authenticated operator context, not caller-supplied actor labels.

## 4. Platform operator identity

### 4.1 Dedicated identity store

Create a platform-specific identity aggregate in additive migrations. The initial logical model is:

- `platform_operators`: public ID, normalized unique email, password hash, full name, status, credential version, email verification timestamp, MFA enrollment state, last authentication metadata, created/updated timestamps.
- `platform_roles`: public ID, unique role code, display name, description, system-role flag, status.
- `platform_permissions`: stable permission code, description, risk classification, status.
- `platform_operator_roles`: operator-to-role assignment with assignment actor, timestamp, and optional expiry.
- `platform_role_permissions`: role-to-permission grants.
- `platform_authentication_events`: successful and failed authentication, MFA challenge outcomes, recovery, credential changes, and revocation events.
- `platform_sessions` or equivalent revocation store if persistent sessions or refresh tokens are introduced.

Use opaque public identifiers for external references and internal numeric IDs for relational joins. Enforce unique normalized operator email and foreign keys. Store only adaptive password hashes (Argon2id using the application's configured encoder); never store plaintext passwords, MFA seeds in plaintext, recovery codes in plaintext, or bearer tokens.

A platform operator may also be a customer tenant user in the future, but that must be represented as two separately authenticated principals. Do not link the identities implicitly by matching email. If an explicit association is ever required, it must be a reviewed, auditable relationship and must not merge credentials or authority.

### 4.2 Lifecycle

Platform operator lifecycle states should include:

- `INVITED`: invitation issued; no privileged access.
- `ACTIVE`: credentials and required MFA enrollment valid.
- `SUSPENDED`: authentication denied; sessions revoked.
- `DISABLED`: access permanently disabled under retention policy.

Require invitation acceptance and verified email before activation. Require MFA enrollment before any privileged platform permission is usable. A change to password, MFA enrollment, status, or role assignments must increment or otherwise invalidate the operator's credential/session version and revoke affected sessions.

Avoid self-service elevation. An operator must not grant themselves a higher-risk role or approve their own privileged role assignment.

## 5. Platform roles and permission model

Platform authorization is a separate least-privilege catalogue. Role names below are proposed starting roles; the permission catalogue and exact grants must be reviewed before implementation.

| Role | Intended responsibility | Explicit exclusions |
|---|---|---|
| `PLATFORM_OWNER` | Platform governance, operator lifecycle, emergency policy oversight | No routine tenant warehouse transaction execution |
| `PLATFORM_ADMIN` | Platform configuration and tenant provisioning coordination | No unrestricted tenant data access; no payment credential access |
| `PLATFORM_SUPPORT` | Customer support workflows and approved, time-limited support grants | No direct database access or standing tenant impersonation |
| `PLATFORM_BILLING_ADMIN` | Future plan, subscription, invoice, and payment operations | No tenant inventory or identity administration |
| `PLATFORM_SECURITY_ADMIN` | Security policy, operator access, incident response, credential revocation | No billing mutation; no unlogged support access |
| `PLATFORM_AUDITOR` | Read-only platform audit and compliance evidence | No state-changing operations or secret disclosure |

### 5.1 Permission catalogue

Permission codes should be action-oriented and namespaced, for example:

- `PLATFORM_OPERATOR_READ`, `PLATFORM_OPERATOR_INVITE`, `PLATFORM_OPERATOR_SUSPEND`, `PLATFORM_OPERATOR_ROLE_ASSIGN`
- `PLATFORM_TENANT_READ`, `PLATFORM_TENANT_PROVISION`, `PLATFORM_TENANT_SUSPEND`, `PLATFORM_TENANT_REACTIVATE`, `PLATFORM_TENANT_CLOSE`
- `PLATFORM_TENANT_METADATA_READ`, `PLATFORM_TENANT_METADATA_UPDATE`
- `PLATFORM_SUPPORT_GRANT_CREATE`, `PLATFORM_SUPPORT_GRANT_REVOKE`, `PLATFORM_SUPPORT_SESSION_START`
- `PLATFORM_AUDIT_READ`, `PLATFORM_SECURITY_POLICY_MANAGE`
- Future-only billing permissions such as `PLATFORM_SUBSCRIPTION_READ` and `PLATFORM_INVOICE_MANAGE`.

These examples are a vocabulary proposal, not a final seeded catalogue. Each permission must carry a risk tier, scope, audit requirement, and allowed assignment policy. Do not use wildcard permissions.

### 5.2 Separation of duties

- Operator invitation and role assignment should require a second authorized actor for privileged roles.
- The actor assigning a role cannot be the same actor accepting or approving a high-risk assignment.
- Tenant closure requires a distinct close permission, explicit tenant-code confirmation, a reason, and an idempotency/operation reference.
- Tenant suspension and reactivation require a reason and durable audit evidence.
- Billing actions, once implemented, must be separated from security administration and tenant operational support.
- Audit readers cannot alter the records they review.
- Break-glass access must not be a normal role or a permanent permission.

## 6. Authentication and token contract

### 6.1 Dedicated platform token profile

Implement a separate platform authentication pipeline. At minimum, platform access tokens must use:

- a dedicated platform issuer or cryptographically distinct trust configuration;
- audience `zirologistics-platform-api` (final value configurable per deployment);
- token-use claim `platform_access`;
- subject equal to `platform_operators.public_id`;
- short expiry (target 5–10 minutes);
- `iat`, `nbf`, `exp`, `jti`, `kid`, and operator credential/session version;
- no tenant membership claim and no tenant permission list.

Use a dedicated signing key pair and key ID for the platform plane, distinct from WMS tenant access tokens. Validate issuer, audience, signature, expiry, not-before, token-use, operator status, credential/session version, and MFA assurance. Key rotation must support a bounded overlap and explicit key retirement.

Platform MFA is mandatory for every privileged operator. Prefer phishing-resistant WebAuthn/passkeys; a TOTP fallback may be considered with documented recovery controls. Email OTP alone is not sufficient for privileged platform administration. Recovery codes must be single-use, securely hashed, and auditable.

Do not introduce long-lived refresh tokens without persistent rotation, replay detection, revocation, and session inventory. The current WMS token implementation does not provide platform sessions; platform implementation must define its own revocation behavior.

### 6.2 Spring Security filter-chain arrangement

The current WMS chain matches `/**`. When platform endpoints are introduced, replace the catch-all arrangement with explicit ordered chains:

1. Platform chain, first order: matcher `/api/platform/v1/**`, platform decoder/converter, platform audience and authorities, stateless sessions, platform CORS policy, deny by default.
2. Tenant chain: matcher `/api/wms/v1/**`, existing WMS decoder/converter and membership resolution, unchanged tenant audience and tenant permissions.
3. Fallback chain: all remaining requests denied unless a separately reviewed public health/operational endpoint is explicitly required.

The platform chain must not reuse the WMS access-token decoder or accept the WMS tenant-selection token. Authentication endpoints are public only by explicit method-and-path allowlist. Disable form login, HTTP Basic, browser sessions, and implicit request caching unless a separately reviewed requirement changes the API security model.

## 7. Tenant lifecycle and provisioning operations

Tenant provisioning, suspension, reactivation, and closure are platform operations. The current command runner remains a useful offline/bootstrap safety mechanism, but it does not substitute for authenticated platform authorization.

For future HTTP operations:

- Derive actor identity from the authenticated platform principal.
- Derive permissions from persisted platform role grants on the server.
- Require an explicit tenant public ID, action, reason, and idempotency reference.
- Validate lifecycle transition from current persisted tenant state under transaction/row lock.
- Persist a platform audit event and tenant-facing audit event in the same transaction where practical.
- Record actor operator ID, permission used, target tenant, before/after state, reason, operation reference, request/correlation ID, timestamp, and outcome.
- Never accept `operatorReference` as a substitute for the authenticated actor.
- Keep the existing explicit non-web command path for controlled recovery or deployment automation, but require a machine identity or tightly controlled local execution policy and record the true execution principal.
- Do not create a public tenant-registration or bootstrap endpoint.

Tenant closure is a lifecycle state transition, not immediate physical deletion. Retention, export, legal hold, data deletion, and eventual purge must be separate policy-controlled processes.

## 8. Privileged support access

Platform support must not receive standing access to tenant operational records.

If support needs to inspect tenant data, introduce a separate **support grant** with:

- tenant scope and permitted resource/module scope;
- explicit support case/ticket reference and human-readable reason;
- requesting operator and independently approving operator;
- start time, expiry, and immediate revocation;
- read-only by default; write access requires a separately approved, narrowly scoped grant;
- visible support-session banner/indicator and complete access audit;
- no access to passwords, password hashes, signing keys, payment secrets, or MFA secrets;
- automatic expiry and revocation on operator suspension or incident response.

Support grants do not change tenant roles, tenant memberships, or tenant permission rows. They are a separate temporary authorization overlay enforced by dedicated server-side checks. No generic “impersonate tenant owner” mechanism is allowed.

## 9. Audit, monitoring, and incident controls

Create an append-only platform audit stream with a dedicated platform operator reference and stable event schema. At minimum record:

- authentication success/failure, MFA events, session creation/revocation;
- operator invitation, activation, suspension, recovery, and role changes;
- tenant provision/lifecycle transitions and metadata changes;
- support grant request, approval, start, access, expiry, and revocation;
- security configuration, key rotation, and break-glass events.

Audit records must not contain passwords, bearer tokens, private keys, MFA seeds, recovery codes, or full payment credentials. Apply UTC instants, request/correlation IDs, stable event codes, actor and subject public IDs, outcome, and a tamper-evident retention/export strategy.

Alert on repeated authentication failures, MFA recovery, privileged role changes, unusual tenant lifecycle actions, support-grant abuse, and break-glass invocation. Define incident procedures for operator compromise, signing-key compromise, and tenant data exposure.

## 10. Break-glass and operational recovery

Break-glass is an exceptional recovery procedure, not a standing platform role.

- Keep it out of public HTTP routes.
- Require a controlled deployment/operations environment, strong operator authentication, explicit incident/change reference, and time-bounded authorization.
- Require two-person approval where operationally possible.
- Emit an immutable audit event before and after the action.
- Restrict actions to the incident scope and revoke the temporary capability immediately afterward.
- Review every use and preserve evidence.
- Never provide a generic command-line switch that silently bypasses authorization or tenant isolation.

The existing bootstrap command is a one-time first-install operation and must remain distinct from break-glass recovery.

## 11. Database and migration strategy

All platform-control-plane schema changes must be additive WMS migrations after the existing migration head. The current baseline contains migrations V1–V27; the next migration number must be confirmed against the actual branch head immediately before implementation.

Do not rewrite V1–V27, frozen schema reference exports, or legacy migrations. Use foreign keys, unique constraints, status checks, indexes for lookup paths, and explicit UTC timestamp semantics. Add migration tests against disposable MySQL 8.4 Testcontainers. Do not run migration tests against local developer databases or the frozen `zirowms_v1` reference database.

Do not add platform columns to tenant roles or encode platform privileges in `roles`, `membership_roles`, or `role_permissions`. The platform identity and permission catalogue must be independently queryable and enforceable.

## 12. API surface (target; not implemented)

Candidate endpoints, subject to API contract review:

- `POST /api/platform/v1/auth/credentials`
- `POST /api/platform/v1/auth/mfa/challenges`
- `POST /api/platform/v1/auth/mfa/verifications`
- `POST /api/platform/v1/auth/sessions/revocations`
- `GET /api/platform/v1/operators`
- `POST /api/platform/v1/operators/invitations`
- `POST /api/platform/v1/operators/{operatorPublicId}/suspensions`
- `GET /api/platform/v1/tenants`
- `POST /api/platform/v1/tenants/provisioning-requests`
- `POST /api/platform/v1/tenants/{tenantPublicId}/suspensions`
- `POST /api/platform/v1/tenants/{tenantPublicId}/reactivations`
- `POST /api/platform/v1/tenants/{tenantPublicId}/closures`
- `GET /api/platform/v1/audit-events`
- `POST /api/platform/v1/support-grants`
- `POST /api/platform/v1/support-grants/{grantPublicId}/revocations`

Do not expose these routes until their request/response schemas, permissions, rate limits, audit semantics, and negative authorization tests are implemented. Avoid generic PATCH endpoints for high-risk lifecycle actions.

## 13. Implementation sequence and acceptance criteria

1. **Schema and domain:** additive platform identity, role, permission, operator-role, role-permission, and audit tables; migration tests and constraints.
2. **Policy catalogue:** finalized role-permission matrix, risk tiers, assignment policy, separation-of-duties tests, and least-privilege defaults.
3. **Authentication:** credential verification, mandatory MFA, dedicated RS256 keys and token profile, session/credential-version revocation, rate limiting, generic errors.
4. **HTTP boundary:** ordered platform, tenant, and deny-all filter chains; route matcher tests prove cross-token rejection.
5. **Operator administration:** invitations, activation, suspension, role assignment and independent approval.
6. **Tenant lifecycle:** replace caller-supplied actor labels with authenticated operator context for platform APIs; preserve command-mode recovery boundary.
7. **Support access:** only after the core control plane is secure; implement time-limited grants and access audit.
8. **Operational hardening:** key rotation, alerting, incident runbooks, audit retention, and security review.

Acceptance requires:
- Platform tokens rejected by tenant endpoints and tenant tokens rejected by platform endpoints.
- Missing/invalid/expired/revoked tokens and inactive operators are denied.
- No tenant role can authorize a platform endpoint.
- No platform role is accepted by tenant membership authorization.
- Cross-tenant references cannot be used to read or mutate tenant data through platform APIs unless a separately authorized support grant permits the exact operation.
- Every privileged state transition records the authenticated operator identity and reason.
- MFA is enforced for all privileged operator sessions.
- Role assignment cannot bypass separation-of-duties or privilege ceilings.
- Migration, unit, MySQL integration, HTTP boundary, negative authorization, and security scans pass on the exact PR head.
- Existing tenant WMS behavior and its token profile remain compatible.

## 14. Explicitly deferred

The following are not part of the initial platform control-plane implementation:

- Subscription checkout, payment gateway integration, payment credential storage, and financial settlement.
- Full SaaS billing and invoice issuance (documented separately).
- Automated tenant data deletion or physical purge.
- Cross-tenant operational reporting.
- Permanent support impersonation.
- A global superuser with unrestricted tenant access.

SaaS entitlements must later be evaluated independently from platform roles and tenant RBAC: a user permission does not imply that a tenant subscription includes the capability.

## 15. Security references

These sources inform the security principles and must be rechecked against the versions used during implementation:

- Spring Security servlet architecture: https://docs.spring.io/spring-security/reference/servlet/architecture.html
- Spring Security multiple HTTP security filter chains: https://docs.spring.io/spring-security/reference/servlet/configuration/java.html
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP Multifactor Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- RFC 7519 — JSON Web Token (JWT): https://www.rfc-editor.org/rfc/rfc7519
- RFC 9700 — OAuth 2.0 Security Best Current Practice: https://www.rfc-editor.org/rfc/rfc9700

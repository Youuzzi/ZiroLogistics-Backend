# Backend Improvement Roadmap

**Status:** Prioritized backend security and operational backlog. Documentation PRs #367 and #368 are merged; frontend foundation may proceed with synthetic data behind replaceable adapters, while real-account and privileged integration remain gated by the acceptance criteria below.
**Baseline:** `main` at `148177826c5eebd56b6134851e5d901809549448`.
**Rule:** Resume backend work based on security risk and frontend integration dependency, not to complete a checklist for its own sake.

## Priority 0 — Required before real account provisioning or privileged integration

### 1. Complete tenant MFA orchestration

**Current state:** Reauthentication grants, dedicated throttling, and factor lifecycle persistence are merged. MFA is disabled by default.

**Remaining work:**
- Enrollment start, encrypted seed generation, and one-time provisioning response.
- TOTP verification and transactional factor activation.
- Purpose-bound step-up challenge and proof lifecycle.
- Atomic one-time proof consumption in the same transaction as privileged mutations.
- Recovery-code generation, digest storage, single-use consumption, and high-assurance recovery.
- Tenant MFA HTTP/application contracts and MySQL concurrency/replay tests.

**Acceptance:** No plaintext factor/recovery secrets persisted or logged; proofs are user/tenant/membership/credential-version/purpose-bound, short-lived, single-use, and transactionally consumed; platform MFA cannot satisfy tenant MFA.

### 2. Design and implement Platform Administrator provisioning

**Current state:** Platform operator persistence, separate JWT trust domain, authentication converter, permission evaluator, and governance services exist. No initial Platform Administrator is created by the current WMS tenant bootstrap command.

**Remaining work:**
- Define a separate, operator-only first-platform-admin provisioning process.
- Bind initial operator identity to secure email verification and MFA enrollment.
- Prevent public/self-service platform registration and prevent tenant bootstrap from silently creating platform privilege.
- Add command/audit/test evidence and a one-time completion marker or equivalent safe control.
- Verify actual deployed platform and tenant signing key material are distinct.

**Acceptance:** A clean database can be provisioned through a controlled, audited, one-time process without default credentials or public bootstrap endpoints; repeat/concurrent attempts fail closed.

### 3. Enforce support-grant access boundaries

**Current state:** Support-grant governance supports requests, approvals, rejection, revocation, constrained scopes, distinct approver, active-tenant checks, bounded grant duration, and audit events. The reviewed source did not contain a runtime consumer that turns an approved grant into scoped support access.

**Remaining work:**
- Define a separate support principal/access context; do not impersonate a tenant user.
- Enforce tenant, purpose, scope allowlist, start/expiry, revocation, and tenant lifecycle state on every access.
- Keep support scopes read-only and limited to approved metadata/configuration/diagnostic/audit metadata.
- Add an explicit, narrowly scoped HTTP/service boundary and audit every access.
- Add MySQL tests for not-yet-active, expired, revoked, suspended-tenant, scope mismatch, approval/revocation races, and audit rollback.

**Acceptance:** An approved grant alone never bypasses tenant authorization; no unrestricted impersonation or transaction/posting access exists.

## Priority 1 — Complete tenant isolation and workflow evidence

### 4. Route-to-query verification

Map every HTTP route and non-HTTP entry point to principal resolution, permission checks, service methods, SQL predicates, and tenant-owned records.

Prioritize:
- Foreign-tenant nested and bulk public IDs.
- Detail/list/search/count/pagination query consistency.
- Not-found versus forbidden behavior and existence disclosure.
- Tenant equality in joins and parent-child ownership.
- Direct service invocation, workers, command runners, and scheduled tasks.

### 5. MySQL integration tests for operational workflows

Add focused MySQL 8.4 Testcontainers tests for:
- Shipping, picking, packing nested identifiers and state transitions.
- Concurrent picking confirmation, package sealing/completion, and shipment shipping.
- Inventory reservation/posting delegation and mismatched tenant/principal inputs.
- Audit event failure rolling back the business mutation.
- Cross-tenant negative cases for bulk IDs and parent-child relationships.

Do not replace stronger MySQL integration tests with mocks or reduce existing regression coverage to make CI green.

### 6. Platform authorization and HTTP boundary

Complete the platform route surface only after the operator permission model and service boundary are stable. Add negative tests for missing permission, stale operator state, invalid MFA freshness, tenant/platform token confusion, and persistence failure. Ensure platform principal authorities are not implicitly trusted.

## Priority 2 — Quality and operational maturity

### 7. Coverage and test evidence

- Capture reproducible JaCoCo reports on the current baseline.
- Map critical business/security risks to tests.
- Set package-level thresholds only after reviewing a stable baseline.
- Preserve evidence labels: runtime verified, MySQL integration verified, source reviewed, partially verified, and unverified.

### 8. Performance and reliability

- Extend beyond read-only baseline scenarios to representative receiving, posting, picking, and shipping workloads.
- Measure latency, throughput, error rate, DB contention, and resource use.
- Define SLOs and capacity targets before setting CI thresholds.

### 9. Production infrastructure and operations

Deferred until the product and integration flows stabilize:
- Secret management and key rotation.
- TLS/reverse proxy and trusted-proxy configuration.
- Monitoring, alerting, structured audit retention.
- Backup/restore and disaster-recovery drills.
- Deployment pipeline, migration release policy, rollback strategy.
- SaaS entitlements, billing, and payment provider selection.

## Frontend integration sequence — gated on documentation completion

PR #367 and PR #368 are merged. After accepting this handoff, frontend foundation may proceed with synthetic data behind replaceable repository/service interfaces. Do not connect real accounts or privileged production-like flows until the following gates are met:
1. Confirm the documentation handoff and API compatibility boundaries.
2. Complete Platform Administrator provisioning design.
3. Complete required tenant MFA orchestration.
4. Verify tenant context and permission enforcement for the API flows being integrated.
5. Provision a dedicated development tenant and Tenant Owner through the controlled bootstrap process.
6. Replace mock repositories with API adapters and run end-to-end tests.

---
Backlog items are not claims of implementation. Re-scope each item against the current source and tests when resumed.

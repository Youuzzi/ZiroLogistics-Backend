# WMS Security Coverage Inventory

**Purpose:** Preserve the security coverage inventory captured while tenant MFA lifecycle persistence (#358) was in progress. PR #358 has since merged; tenant MFA factor persistence exists, while end-to-end enrollment, verification, step-up, recovery, and one-time proof consumption remain incomplete and MFA remains disabled by default. This is an audit planning artifact, not a security certification or proof that every listed control is implemented.

## Baseline

The current main branch at the time of this inventory is `102aae9642dd8f3d59ed47b91a54dab13fd7cd36`. Existing security reviews explicitly state that tenant isolation and route-to-query verification remain partially verified. This inventory preserves that evidence boundary.

## Parallel work boundaries

- This documentation-only slice does not modify authentication, authorization, MFA, migrations, or runtime behavior.
- The original review constraint to avoid MFA lifecycle changes while PR #358 was in review is historical and no longer an active restriction.
- Follow-up implementation slices should be opened only after a source trace identifies a concrete gap and a focused test reproduces or proves it.

## Evidence already present in repository

| Domain | Existing evidence found | Verification boundary |
|---|---|---|
| Tenant identity/membership resolution | Membership access service/repository; tenant JWT converter tests; live permission revocation MySQL integration tests | Selected identity and revocation paths, not every service/query |
| HTTP auth boundary | Security chain tests; enabled/disabled/auth endpoints disabled tests; controller auth-boundary tests across major WMS modules | Tested routes/configurations only |
| Tenant isolation | Boundary tests and MySQL tenant isolation tests for warehouse, reporting, identity, replenishment, transfer, inventory, and selected outbound paths | Not a full route-to-query trace |
| Tenant permission governance | Permission policy, risk registry, role management tests and migration integration tests | Specific policy/role paths; verify every mutation and sensitive permission |
| Platform control plane | Platform JWT/filter-chain, permission evaluator, credential-auth, lifecycle service tests | Separate platform paths; verify full route and service coverage independently |
| MFA foundation | TOTP service, replay-protection, throttling, reauthentication-grant migration tests; lifecycle persistence in PR #358 | MFA persistence/orchestration is not complete until orchestration and HTTP/application integration are implemented and tested |
| Tenant lifecycle | Tenant lifecycle/provisioning/profile integration tests | Review state transitions, authorization, idempotency, and actor attribution |
| Async/command execution | A worker/command inventory exists in prior authorization audit | Worker and command tenant context/authority need explicit evidence, not assumed from HTTP tests |

## Verification backlog (ordered by risk)

### S16-PAR-01 — Route-to-query tenant trace

For each controller mapping, trace route -> authenticated principal -> operation permission -> application service -> repository/SQL -> tenant-owned tables. Cover detail, list/search, pagination/count, nested references, mutation, and state transitions separately. Prioritize mixed-tenant parent/child identifiers and bulk inputs.

**Acceptance:** every route has a trace entry; every tenant-owned read/write has tenant and parent ownership conditions; negative MySQL tests cover the highest-risk nested/bulk paths; findings are classified by severity and remediation.

### S16-PAR-02 — Authorization at non-HTTP entry points

Inventory scheduled workers, command runners, event consumers, and direct application-service entry points. Confirm each has a deliberate actor/tenant context, least privilege, and fail-closed behavior. HTTP authentication tests are not evidence for these entry points.

**Acceptance:** execution identity/context is explicit, tenant data access is scoped, unauthorized invocation is tested, and audit attribution is defined.

### S16-PAR-03 — Sensitive permission and governance mutations

Review role creation/update, permission grants, membership-role assignments, invitations, tenant profile changes, lifecycle changes, and platform support-grant paths. Test unauthorized, inactive actor/tenant/role, stale membership, replay, and concurrent mutation cases where applicable.

**Acceptance:** each mutation has a permission gate and auditable actor; critical operations have purpose-bound step-up where required; race conditions are tested against MySQL.

### S16-PAR-04 — Authentication/session and credential invalidation

Trace credential-version claims, membership resolution, token issuance/validation, revocation and password changes. Test stale token after credential-version or membership change, malformed claims, expiry, token profile confusion, and fail-closed database resolution.

**Acceptance:** tests prove invalidated tokens cannot regain access and token profiles remain distinct.

### S16-PAR-05 — Platform/tenant boundary

Review platform filter chains, issuer/audience/claims, permission evaluator, platform services, and any support-grant path. Confirm platform identity cannot directly authorize tenant transaction endpoints and tenant roles cannot authorize platform endpoints. Verify support access is tenant-, purpose-, time-, and revocation-bound and audited.

**Acceptance:** negative cross-plane HTTP and service tests; no implicit privilege bridging.

### S16-PAR-06 — Security failure behavior and observability

Review persistence/authentication failures, audit-write failures, error responses, throttling behavior, and log contents for secrets/OTP/token/PII leakage. Ensure security-critical read/decision failures reject requests. Test generic responses for unknown identity/resource cases.

**Acceptance:** focused failure-path tests; no secret material in logs or public errors; failure policy documented and verified.

## Evidence labels

Use only the following labels in follow-up work:
- **MySQL integration verified** — real MySQL schema/query behavior was exercised.
- **HTTP boundary verified** — request, authentication, and principal forwarding were exercised.
- **Service boundary verified** — service behavior/predicate reviewed or tested without full MySQL proof.
- **Source reviewed** — code inspected without runtime proof.
- **Not covered** — no sufficient evidence exists.

A test class name or CI-green status alone does not justify a stronger evidence label.

## Exit rule

Do not declare Stage 16 complete until critical findings are closed, high-risk route/query and non-HTTP paths have adequate evidence, MFA orchestration and recovery behavior are implemented and verified, platform/tenant boundaries have negative tests, and the final report states residual risks and deployment-dependent controls. This inventory is intentionally conservative and does not claim production readiness.

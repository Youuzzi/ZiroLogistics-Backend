# Single-Operator Tenant Role Assignment Gap Audit

**Status:** Verified current-state functional gap and proposed safe resolution. Documentation only; no role grants or runtime policy changes.

## 1. Product requirement

A small or UMKM warehouse may have one real human operator who receives goods, records inbound work, puts stock away, creates shipments, picks, packs, ships, and performs routine internal transfers. The same tenant authorization model must support this staffing reality without weakening high-impact approval controls.

Multi-role support is necessary but not sufficient. A membership must be able to obtain the exact operational permissions needed for the tenant's explicitly selected operating profile.

## 2. Current implementation facts

### Tenant provisioning

- `WmsTenantProvisioningService` creates one `TENANT_OWNER` role and assigns it to the initial owner membership.
- The provisioning path invokes `WmsTenantRoleTemplateProvisioner` to create the 13 tenant-scoped system role templates.
- The 13 templates are created as available role definitions; provisioning does not assign all operational roles to the owner.
- `TENANT_OWNER` has a 30-code least-privilege baseline and does not include inventory posting, shipment commit, transfer dispatch/receive, stock-count approval or adjustment approval/posting.

### Self-assignment restrictions

`WmsTenantPermissionPolicy.selfAssignmentRestrictedSystemRoleCodes()` currently blocks an acting user from assigning these system roles to their own membership:

- `SYS_INBOUND_CONTROLLER`
- `SYS_OUTBOUND_CONTROLLER`
- `SYS_SHIPPING_CLERK`
- `SYS_INVENTORY_APPROVER`
- `SYS_TRANSFER_DISPATCHER`
- `SYS_TRANSFER_RECEIVER`

The guard is enforced in `WmsRoleManagementService.assignRole` by comparing the acting principal's global user public ID with the target membership's user public ID.

### Permission delegation

The custom-role delegation allowlist contains 20 explicit codes and does not include operational commit permissions such as `WMS_INBOUND_RECEIPT_POST`, `WMS_SHIPPING_SHIP`, `WMS_TRANSFER_DISPATCH`, or `WMS_TRANSFER_RECEIVE`. A tenant owner cannot work around the system-role self-assignment restriction by creating a custom role with those permissions.

## 3. Functional impact for a tenant with one human

The current rules create a practical dead end:

| Workflow | Available operator template | Additional template needed | Current self-assignment outcome |
|---|---|---|---|
| Inbound | `SYS_INBOUND_OPERATOR` (create/process receipt and putaway) | `SYS_INBOUND_CONTROLLER` (post receipt) | Owner may assign operator role but cannot self-assign controller role. |
| Outbound | `SYS_OUTBOUND_OPERATOR` (allocate, pick, pack) | `SYS_OUTBOUND_CONTROLLER` (release) and `SYS_SHIPPING_CLERK` (final ship) | Owner cannot self-assign either restricted role. |
| Inter-warehouse transfer | No single template contains both dispatch and receive authority. | `SYS_TRANSFER_DISPATCHER` and `SYS_TRANSFER_RECEIVER` | Owner cannot self-assign either restricted role. |
| Stock count / adjustment | `SYS_INVENTORY_CONTROLLER` (prepare and submit) | `SYS_INVENTORY_APPROVER` (review/approve/post) | Owner cannot self-assign approver. This is intentionally high-risk and should remain independently governed. |
| Replenishment | `SYS_REPLENISHMENT_OPERATOR` | Reversal is intentionally excluded from the operator role. | Routine confirmation is available; A1 reversal remains a separate controlled action. |

**Conclusion:** The current system proves that one membership can hold multiple roles, but it does not yet provide a safe supported path for a sole tenant owner to activate all routine operational capabilities. This is a real product gap for the UMKM profile, not a reason to broaden the custom-role allowlist or automatically grant permissions.

## 4. Risk distinction

Do not treat every restricted role as the same risk.

- Inbound posting, outbound release/shipping and transfer dispatch/receive are classified C1 in the permission risk registry, require fresh MFA and audit, and are mapped to workflow SoD groups.
- Stock-count review/approval, inventory-adjustment approval/rejection/posting and replenishment confirmation reversal are classified A1 and require fresh MFA and audit.
- C1 indicates a critical operational commit. It must not be silently granted, but a tenant may be allowed to select a bounded single-operator operating mode with explicit compensating controls where the workflow threat analysis approves it.
- A1 approval/reversal authority remains independently governed. A single-operator profile must not turn a maker into their own independent approver or allow reversal of their own confirmation.

Risk class alone does not decide waivability. The rule catalogue, SoD group, workflow semantics and compensating controls must all be reviewed.

## 5. Recommended safe resolution

Introduce a dedicated, auditable **operating-profile activation** workflow rather than removing the self-assignment guard or expanding custom-role delegation.

### UMKM single-operator activation

1. Tenant Owner explicitly selects the Small / UMKM single-operator mode; no profile or role is enabled automatically by tenant size or user count.
2. Require fresh MFA and an explicit confirmation that the same human will execute the selected operational stages.
3. Present a bounded list of eligible C1 operational roles and their workflow effects. Do not offer A1 approver or reversal permissions through this path.
4. Resolve every selected role against the immutable system-role template and the approved tenant profile rule catalogue.
5. Assign the selected roles to the owner's existing active membership in one transaction.
6. Record one audit event containing actor membership, target membership, profile, selected role codes, policy version, reason, MFA freshness and outcome.
7. Evaluate the corresponding configurable SoD rules at action time. Single-operator execution is allowed only for the selected C1 workflows and only with the compensating controls below.
8. Preserve an explicit route to tighten the profile later. Tightening policy must not rewrite historical actor evidence.

### Required compensating controls

- Fresh MFA at activation and at each C1 critical action where the registry requires it.
- Exact permission checks and current membership/role re-resolution on every request.
- Full tenant audit for activation and operational commits.
- No bypass of picking/packing, quantity reconciliation, serial/lot validation, inventory locks, idempotency, or atomic ledger posting.
- Mandatory independent review for configured stock-count variance thresholds, sensitive/serial discrepancies and all adjustment approvals.
- Mandatory independent actor for A1 replenishment reversal.
- Controlled internal transfer only; damage, shortage, cross-tenant movement or high-value thresholds route to independent review.
- Clear UI indication that single-operator mode is active and which controls remain independent.

### Medium and Enterprise

- Medium uses the same role templates but defaults to separate actors for exception, high-value and variance-driven operations. Tenant administrators may tighten controls and configure only approved rule modes.
- Enterprise requires independent actors for the relevant C1 workflow groups and A1 approvals, with no single-operator waiver for those controls.

## 6. Explicitly prohibited shortcuts

- Do not remove `selfAssignmentRestrictedSystemRoleCodes()` globally.
- Do not add all 94 risk-registry permissions to the custom-role allowlist.
- Do not automatically assign all 13 system roles to every Tenant Owner.
- Do not infer UMKM mode from tenant name, user count, plan, or lack of other members.
- Do not let a profile grant permissions independently of RBAC.
- Do not permit self-approval for stock-count variance, inventory adjustment, or reversal.
- Do not weaken fresh MFA, audit, tenant isolation, inventory posting invariants, or current-membership revalidation.

## 7. Implementation work packages

1. Add a typed operating-profile and eligible-role catalogue with explicit C1/A1 classification and unit tests.
2. Add an activation command requiring a dedicated governance permission, fresh MFA and reason; define the first-owner bootstrap case explicitly without creating credentials or grants outside the user's confirmation.
3. Implement transactional role assignment with role-template parity, versioning, idempotency and audit.
4. Add MySQL integration tests for one-owner activation, rejected A1 role selection, cross-tenant target rejection, repeated activation, rollback and audit atomicity.
5. Add service-level tests proving that profile mode relaxes only the selected C1 actor comparison and cannot bypass A1 controls.
6. Add UI/API contract and DAST coverage before exposing the activation path.
7. Add migration/backfill only after the policy storage and compatibility contract is reviewed.

## 8. Acceptance criteria

- A sole owner can explicitly activate an approved UMKM operating profile and receive only the selected C1 operational roles.
- No A1 approval or reversal permission is granted by this workflow.
- Role assignment, profile activation, and policy evaluation are tenant-scoped, atomic, versioned and audited.
- The default profile remains conservative until the tenant owner explicitly activates the supported mode.
- The system never fabricates a second actor or records a self-action as independent approval.
- Medium/Enterprise policy can tighten controls without changing identity or RBAC architecture.
- MySQL 8.4 Testcontainers, Java 21 CI, CodeQL, dependency scan and authenticated DAST pass.

## 9. References

- NIST CSRC, Role Based Access Control FAQs: https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST CSRC, Separation of Duty: https://csrc.nist.gov/glossary/term/separation_of_duty
- Microsoft Learn, Manage warehouse workers: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/manage-warehouse-workers
- AWS SaaS Architecture Fundamentals, Tenant isolation: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html

---
This audit records the inspected current implementation and proposes a controlled product capability. It does not claim the UMKM activation workflow already exists.

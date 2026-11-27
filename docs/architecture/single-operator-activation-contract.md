# UMKM Single-Operator Activation Contract — Stage 16

## Problem statement
Tenant provisioning gives the initial owner only the Owner template. Multi-role aggregation does not close the operational gap because selected system roles are self-assignment restricted and the custom-role delegation allowlist excludes critical operational commit permissions.

## Intended experience
A small warehouse may explicitly activate **Single-Operator Operating Mode** after onboarding. Activation is not automatic and does not turn Owner into a universal superuser. The user selects a bounded profile and explicitly reviews which operational roles/actions become available.

## Safety boundary
- Fresh MFA for activation, deactivation and role-selection changes.
- Revalidate active tenant membership and authorization at action time.
- Grant only explicitly approved routine C1 roles; do not grant A1 approval/reversal roles.
- Preserve tenant isolation, atomic posting, audit, pick/pack/reconciliation, lot/serial and inventory invariants.
- Stock-count variances above approved thresholds and adjustment approvals remain independent.
- Replenishment reversal requires an independent actor; mode cannot bypass it.
- Internal transfers retain dispatch/receive evidence and configured safeguards.
- Revocable and auditable; deactivation removes mode-derived grants.
- Fail closed if state, membership, policy, or MFA evidence cannot be validated.

## UX and disclosure
Before activation, disclose that one operator may perform multiple routine steps while independent controls remain for protected approvals/reversals. Show permission groups, impact, explicit confirmation, activation status/timestamp/actor and audit trail. Never silently activate at tenant creation.

## Bounded scope
Candidate C1 workflows: routine inbound receipt/posting, routine outbound execution/shipping commit, and internal transfer operations under profile policy. Exact permission codes must come from canonical policy and be reviewed during implementation. No wildcards.

## Rollout
1. Contract/threat review.
2. Typed policy resolver and profile defaults.
3. Activation state and grant model review; persistence without touching frozen V1.
4. Fresh-MFA endpoint/service and transactional audit.
5. Tests for activation/revocation, MFA freshness, permission ceiling, tenant isolation, concurrency and failures.
6. Workflow guard integration and verify independent A1 controls.
7. UI only after backend contract and enforcement are certified.

## Acceptance criteria
- No automatic role grants at tenant bootstrap.
- Explicit, MFA-fresh, tenant-bound, revocable, auditable activation.
- A1 and independent actor controls unchanged.
- Unknown/missing state denies protected actions.
- All selections bounded by allowlisted policy.

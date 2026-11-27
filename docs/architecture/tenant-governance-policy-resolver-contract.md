# WMS Tenant Governance Policy Resolver Contract

**Status:** Target policy-resolution contract. No runtime resolver or tenant-configurable governance enforcement is claimed by this document.

## Purpose
Define a typed, deterministic policy resolution boundary before profile rules are wired into operational workflows. This contract is additive and does not authorize permissions or change the frozen V1 schema.

## Policy dimensions
- **MANDATORY**: cannot be disabled by tenant profile/configuration.
- **CONFIGURABLE**: tenant may select from a bounded, validated set.
- **THRESHOLD**: validated numeric boundary with explicit units and safe bounds.
- **ADVISORY**: warning/recommendation only; never blocks or grants authorization.

## Resolution inputs
Tenant identity, active profile (SMALL, MEDIUM, ENTERPRISE), validated overrides, and workflow/risk context. Tenant identity must derive from authenticated membership, not request input. Missing, malformed, stale, or contradictory state fails closed for protected operations.

## Precedence
1. Security, tenant isolation, authorization and mandatory SoD invariants.
2. Valid override for configurable/threshold rules only.
3. Profile defaults.
4. Safe system defaults.

Overrides cannot weaken mandatory invariants, broaden caller permissions, bypass independent approval, or change tenant identity.

## Typed model requirements
Use enums/value types for rule category, profile, workflow, and rule IDs; avoid free-form string comparisons at call sites. Resolution results include rule ID, effective value, source (mandatory/override/profile/default), and policy version for audit. Unknown IDs and invalid values are rejected; no silent coercion.

## Initial policy scope
Inbound receipt posting, outbound shipping commit, transfer dispatch/receive, stock count review/approval, inventory adjustment approval/posting, replenishment confirmation/reversal. Profile presets may vary routine C1 controls only where explicitly reviewed. A1 approvals, independent reversals, atomic posting, tenant isolation, identity, audit and fresh MFA for critical actions remain mandatory.

## Compatibility and rollout
No schema or migration changes in resolver-contract step. Implement read-only deterministic resolver with unit tests first. Persistence only after schema/ownership review. Integrate workflow by workflow, retaining current behavior when no policy exists. Require positive, negative, and tenant-isolation tests.

## Acceptance criteria
- Pure deterministic resolution.
- Missing/invalid policy never grants access.
- Profile defaults explicit and versioned.
- Overrides cannot alter mandatory rules.
- Provenance testable.
- Tests cover profiles, precedence, invalid/unknown values, fail-closed cases.
- No workflow wired to unreviewed policy in this contract PR.

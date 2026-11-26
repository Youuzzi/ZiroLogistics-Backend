# Stage 16 — Tenant Governance Policy Rule Contract

**Status:** Implementation contract for the next governance-policy increment. This document defines policy semantics; it does not introduce runtime configuration or relax current controls.

## 1. Purpose and boundaries

The tenant governance policy answers **which workflow actor relationships are mandatory, configurable, threshold-driven, or advisory** for a tenant. It does not define identity, grant permissions, replace RBAC, or establish tenant isolation.

The enforcement layers remain separate:

1. **Identity and membership:** authenticated global user plus one active tenant membership. The schema enforces one membership per `(tenant_id, user_id)`.
2. **RBAC:** active role assignments are aggregated into the current effective permission set.
3. **Governance policy:** determines which actor combinations are allowed for a workflow transition.
4. **Enforcement:** workflow services compare persisted actor evidence before performing state changes or inventory postings.

A profile is a source of safe defaults, not an authorization shortcut.

## 2. Policy classification

| Classification | Meaning | Profile behavior |
|---|---|---|
| Mandatory | Required control for the workflow's integrity or high-impact approval boundary. | Cannot be disabled or weakened by any tenant profile. |
| Configurable | Tenant may choose from a bounded set of safe policies. | Profile supplies default; tenant governance administrator may change within the allowed range. |
| Threshold-driven | Rule activates or strengthens at defined quantity/value/risk thresholds. | Thresholds are bounded by platform policy; missing or invalid thresholds fail closed. |
| Advisory | Warning or operational guidance without blocking a transition. | May be enabled/disabled; never substitutes for a mandatory rule. |

An unclassified rule is not implicitly advisory. Unknown rule codes, unsupported values, missing mandatory configuration, and malformed policy versions must fail closed for the protected transition.

## 3. Initial rule catalogue

The rule identifiers below are stable policy concepts proposed for implementation. They must be mapped to actual persisted actor fields and service methods before runtime rollout.

| Rule code | Workflow / actor comparison | Classification | Initial default |
|---|---|---|---|
| `INBOUND_RECEIPT_CREATOR_POSTER` | Receipt creator user versus posting user. | Configurable | UMKM may allow one operator with fresh MFA, mandatory audit and strict discrepancy controls; Enterprise requires separation. |
| `OUTBOUND_SHIPMENT_CREATOR_SHIPPER` | Shipment creator user versus final shipper user. | Configurable | UMKM may allow one operator only after all required picking/packing reconciliation; fresh MFA and audit remain mandatory. Enterprise requires separation. |
| `TRANSFER_DISPATCHER_RECEIVER` | Receiver user versus every user that dispatched any quantity on the transfer. | Configurable / threshold-driven | UMKM may allow one operator for controlled internal transfers with complete transit and discrepancy evidence; shortages, damage or high-value transfers require independent review. Enterprise requires separation. |
| `STOCK_COUNT_COUNTER_REVIEWER` | Reviewer user versus latest final counter user for the reviewed line. | Threshold-driven | UMKM may self-submit routine counts within configured variance tolerance; a variance above tolerance or sensitive/serial discrepancy requires independent review. Enterprise requires independent review. |
| `STOCK_COUNT_CREATOR_APPROVER` | Approver user versus count creator when approval is required. | Mandatory | Any adjustment-producing variance approval must be independent; no self-approval. |
| `STOCK_COUNT_REVIEWER_APPROVER` | Approver user versus header/line reviewers and all latest final counters when approval is required. | Mandatory | Variance approval remains independent; routine no-variance counts need not invent an approval step. |
| `INVENTORY_ADJUSTMENT_CREATOR_APPROVER` | Approver user must differ from adjustment creator. | Mandatory | Enforced |
| `INVENTORY_ADJUSTMENT_CREATOR_POSTER` | Poster user must differ from adjustment creator. | Mandatory | Enforced |
| `INVENTORY_ADJUSTMENT_APPROVER_POSTER` | Whether approver and poster must differ. | Configurable | Two-person default: approver may also post after independent approval; Enterprise profile may require a third actor. |
| `REPLENISHMENT_CONFIRMATION_REVERSAL` | Reversal user and membership must differ from the original confirmation actor. | Mandatory | Enforced; implementation tracked in Stage 16 PR #341. |
| `REPLENISHMENT_TASK_ASSIGNEE_CONFIRMER` | Whether the assigned operator may confirm their own task. | Advisory | Same operator permitted; confirmation still requires exact workflow and inventory-post permissions. |

### Actor identity semantics

- Compare the global user identity for human separation; also compare tenant membership IDs to preserve the exact persisted membership audit trail.
- A membership is unique per tenant/user pair today. Retaining both identifiers in rule evaluation is defense in depth and protects future identity-model changes.
- For multi-actor workflows, compare against **all** relevant historical actors, not only a header's first actor.
- A missing actor reference is not evidence of independence. Mandatory rules fail closed if required actor evidence cannot be resolved.
- System identities, platform operators and tenant users are distinct actor domains; cross-domain equivalence must never be inferred from a matching string identifier.

## 4. Profile defaults

| Rule family | Small / UMKM | Medium | Enterprise |
|---|---|---|---|
| Inbound creator/poster | Configurable: single operator with compensating controls | Separate for discrepancies / configured thresholds | Mandatory separation |
| Shipment creator/shipper | Configurable: same operator after complete pick/pack reconciliation | Separate for high-risk shipments | Mandatory separation |
| Transfer dispatcher/receiver | Configurable: controlled internal transfer only | Separate for high-risk / exception transfers | Mandatory separation |
| Stock-count independent review/approval | Threshold-driven: routine counts may self-submit; variance approval independent | Independent review above configured variance threshold | Mandatory independent review / approval |
| Adjustment creator/approver and creator/poster | Mandatory | Mandatory | Mandatory |
| Adjustment approver/poster | Same approver may post after an independent approval | Configurable, default separate for high-value adjustments | Separate by default |
| Replenishment reversal | Mandatory independent actor | Mandatory independent actor | Mandatory independent actor |
| Replenishment assignee/confirmer | Same operator permitted | Same operator permitted | Configurable |

These defaults preserve a workable operating model for small warehouses without turning the product into an uncontrolled single-user ledger. A small operator may complete routine receiving, dispatch and controlled internal transfer when the tenant explicitly selects the bounded single-operator mode. Fresh MFA, exact permissions, mandatory audit, inventory reconciliation, idempotency and exception thresholds remain in force. Variance approvals, adjustment approvals and replenishment reversals remain independent. Enterprise tenants can require stricter separation throughout the workflow.

## 5. Invariants that profiles cannot change

- Tenant isolation and tenant-scoped database predicates.
- Authentication, active membership resolution and live permission revocation.
- Exact RBAC permission checks and the narrow custom-role delegation allowlist.
- Platform/tenant identity, token issuer, audience and security-chain separation.
- Fresh MFA for protected platform and tenant critical actions as currently defined.
- Atomic inventory ledger/balance posting, idempotency, locking and rollback.
- Mandatory maker-checker rules in Section 3, plus every configurable/threshold-driven rule that is active in the resolved tenant policy.
- Durable audit evidence for state transitions and reversals.
- No wildcard, implicit superuser, profile-derived permission grants, or self-approval.
- No tenant setting may rewrite historical actor evidence.

## 6. Proposed policy data contract

The first implementation should use a typed, versioned policy representation rather than arbitrary JSON interpreted at runtime.

Minimum conceptual fields:

- `policy_code`: stable identifier;
- `policy_version`: monotonic version;
- `profile_code`: SMALL, MEDIUM or ENTERPRISE;
- `rule_code`: one identifier from the approved catalogue;
- `classification`: MANDATORY, CONFIGURABLE, THRESHOLD_DRIVEN or ADVISORY;
- `enabled`: effective state, constrained by classification;
- `parameters`: typed and bounded rule parameters only;
- `effective_from` and optional `effective_until`;
- `updated_by_membership_id` and audit reference.

The policy resolver should return an immutable effective-policy snapshot for the transition. The service must still validate the actor history and current tenant scope inside the same transaction as the workflow state change. A policy snapshot must not be cached beyond the consistency boundary required by the transition.

No database migration should be added until the policy catalogue, override constraints, policy version concurrency, audit behavior, tenant lifecycle behavior and rollback semantics have separate reviewed designs.

## 7. Enforcement and rollout sequence

1. Add a typed rule catalogue and validator with unit tests for all rule codes and profile defaults.
2. Add a read-only effective-policy resolver backed by immutable platform defaults; no tenant overrides initially.
3. Route existing hard-coded mandatory SoD checks through the resolver without changing their effective behavior.
4. Add MySQL integration tests proving the resolver and actor checks are evaluated in the same tenant transaction.
5. Add bounded tenant overrides only for CONFIGURABLE and THRESHOLD_DRIVEN rules, with optimistic versioning and audit.
6. Add profile migration/backfill with explicit defaults; never infer a weaker profile from tenant size or user count.
7. Add a parity test: every registry SoD group maps to a rule code, enforcement point, persisted actor evidence and negative integration test.
8. Roll out behind a fail-closed feature gate and retain rollback to mandatory platform defaults.

## 8. Acceptance criteria

- Every rule has one authoritative definition, classification, actor comparison key, scope, enforcement service and negative test.
- Mandatory rules cannot be disabled by profile or tenant override.
- Configurable options are enumerated and validated; arbitrary values are rejected.
- Multi-role permission aggregation cannot bypass SoD.
- Policy changes are tenant-scoped, versioned, audited and atomic.
- Existing workflow behavior remains unchanged until a reviewed implementation PR explicitly changes it.
- MySQL 8.4 Testcontainers, Java 21 CI, dependency scan, CodeQL and authenticated DAST pass on the exact commit.

## 9. References

- NIST CSRC, Role Based Access Control FAQs: https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST CSRC, Separation of Duty: https://csrc.nist.gov/glossary/term/separation_of_duty
- Microsoft Learn, Manage warehouse workers: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/manage-warehouse-workers
- Microsoft Learn, Cycle counting: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/cycle-counting
- AWS SaaS Architecture Fundamentals, Tenant isolation: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html

---
This is an engineering contract for implementation planning, not a claim that a configurable runtime policy engine already exists.

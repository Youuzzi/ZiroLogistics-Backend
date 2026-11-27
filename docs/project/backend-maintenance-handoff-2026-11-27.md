# Backend Maintenance Handoff — 2026-11-27

**Repository:** `Youuzzi/ZiroLogistics-Backend`  
**Checkpoint branch:** `main`  
**Checkpoint commit:** `148177826c5eebd56b6134851e5d901809549448`  
**Status:** Backend feature work remains paused until the repository-wide documentation cleanup is reviewed and merged. Frontend foundation work is deferred until that cleanup is complete. This is a temporary handoff, not a declaration of security or production readiness.

## 1. What was completed immediately before the handoff

| PR | Scope | Result |
|---|---|---|
| #354 | Tenant MFA reauthentication grants | Merged; Backend CI, CodeQL, DAST, and OSV passed |
| #355 | Dedicated tenant MFA throttling | Merged; Backend CI, CodeQL, DAST, and OSV passed |
| #357 | Tenant credential version in authenticated principal | Merged |
| #358 | Tenant MFA factor lifecycle persistence | Merged at `6b1432a768385e5cc745ace642051de56e7dd8da`; Backend CI, CodeQL, DAST, and OSV passed |
| #362 | JWT trust-domain separation startup guard | Merged |
| #364 | Tenant-scoped inventory mutation predicates | Merged |
| #365 | Outbound fulfillment tenant-isolation source review | Merged; documentation/source review only |
| #366 | Real RSA cross-signature boundary tests | Merged at `148177826c5eebd56b6134851e5d901809549448`; Backend CI, CodeQL, DAST, and OSV passed |

PR #366 adds cryptographic boundary tests using distinct generated RSA key pairs and actual Nimbus JWT encoders/decoders. The platform decoder rejects a tenant-signed token and the tenant decoder rejects a platform-signed token even when claims are shaped to resemble the receiving token profile.

Earlier source reviews and implementation slices are recorded in the audit documents. A source review is not equivalent to MySQL integration or end-to-end verification.

## 2. Repository checkpoint

At the checkpoint commit:

- Java production source files: 194.
- Java test source files: 136.
- WMS Flyway migration files: 32 (V1–V32).
- Spring Boot: 3.5.16.
- Java: 21.
- MySQL target: 8.4.
- WMS runtime entry point: `WmsRuntimeApplication`.
- Maven runtime profile: `wms-runtime`.
- Flyway location: `classpath:db/wms-migration`.

The source tree contains WMS domains for adjustment, authorization, bootstrap, governance, identity, inbound, inventory, outbound, packing, picking, platform, putaway, replenishment, reporting, security, shipping, stock count, tenancy, transfer, and warehouse.

## 3. Current capability and evidence boundaries

### Tenant identity and authorization

Tenant authentication uses a tenant-selection token followed by a tenant-bound access token. The authenticated principal resolves current membership and permission state. Credential-version enforcement is present so credential changes invalidate stale access context.

The 13 tenant operational role templates and permission governance must be understood from the current policy and source; a role name alone does not establish operational posting rights. Tenant Owner is not implicitly granted all inventory posting permissions.

### Platform control plane

Platform operator identity and a separate platform JWT security boundary exist. Platform tokens use a separate configuration namespace, issuer, audience, RSA key material, token-use profile, and decoder. The startup guard rejects configured key/issuer/audience collisions across trust domains.

The reviewed platform authentication converter requires fresh-enough MFA evidence and resolves current operator status and credential version. Platform principals do not receive authorities automatically; services must use persisted permission evaluation.

**Important gaps:**
- No complete platform HTTP controller surface was identified in the reviewed source slice.
- No runtime support-grant consumer/access enforcement path was identified.
- Support-grant governance records and request/approve/reject/revoke logic do not themselves grant tenant data access.
- Actual deployed platform and tenant PEM values still require secure distinctness verification.

### Tenant MFA

Merged work now includes:
- Reauthentication grant persistence and purpose binding.
- Dedicated MFA throttling.
- Factor lifecycle persistence for pending, active, and revoked factors.
- Replay watermark handling at factor activation.
- Encrypted seed persistence and ciphertext removal on revocation.

Still incomplete:
- Enrollment start and provisioning response.
- TOTP verification and factor activation orchestration.
- Step-up challenge creation and verification.
- Atomic, purpose-bound proof consumption with protected governance mutations.
- Recovery-code lifecycle and high-assurance recovery.
- Tenant MFA HTTP/application integration and end-to-end MySQL tests.

MFA remains disabled by default. Do not enable it by setting a configuration flag until the complete orchestration and required key management are implemented and verified.

### Tenant isolation and inventory integrity

Reviewed source slices cover warehouse/location, inventory, receiving/putaway, transfers, replenishment, stock count, shipping, picking, and packing. The reviews did not confirm a concrete cross-tenant vulnerability in the reviewed code. Tenant-scoped SQL predicates and tenant-equality joins are present in the inspected paths, including the inventory mutation boundary fixed in #364.

This is not a complete certification. Remaining evidence includes:
- Full route-to-query inventory for all controllers and service entry points.
- MySQL negative tests for foreign-tenant nested/bulk public identifiers.
- Not-found/forbidden non-disclosure semantics.
- Concurrent workflow transition tests.
- Audit rollback and downstream posting/reservation boundary tests.
- Background worker and command-runner tenant/actor context review.

### Runtime and database safety

The WMS runtime uses a dedicated database and migration path. The current migration set is V1–V32. All applied migrations must remain immutable; future schema changes must be additive (V33+).

Secure defaults in `application-wms.properties` include:
- Tenant JWT disabled.
- Platform JWT disabled.
- Tenant MFA disabled.
- Flyway disabled outside the dedicated Compose configuration.
- CORS deny-by-default.
- Anonymous auth endpoint switch disabled by default.

The historical `zirowms_v1` and legacy `zirologistics_db` databases must not be used as WMS migration targets.

## 4. Initial account and tenant bootstrap status

A controlled non-web bootstrap command exists. It is designed for a completely empty dedicated WMS database and creates:
- Initial tenant and tenant code.
- Initial global user record.
- Active tenant membership.
- Tenant Owner role and the role's canonical permission template.
- Canonical tenant role templates.
- A one-time completed bootstrap marker.

It performs the database changes transactionally and refuses to run when existing users, tenants, memberships, roles, or a completed marker are present.

**It does not create a Platform Administrator, platform operator identity, or platform credentials.** The initial Tenant Owner and Platform Administrator are distinct identities and require separately designed provisioning. No first account or credentials have been created as part of this handoff.

The first tenant bootstrap should be performed only after the target database is verified, platform-admin provisioning is designed, security configuration is ready, and the owner explicitly approves the one-time operation. Secrets must be injected ephemerally from a secure secret store and never committed or printed.

## 5. Backend work intentionally paused

Do not open broad backend refactoring or new audit slices while frontend foundation work is underway. Resume only for:
- A blocker to a frontend API contract or integration.
- A confirmed security/integrity defect.
- The specifically planned bootstrap and identity provisioning work.
- A prioritized backlog item when the frontend integration plan requires it.

No production deployment, payment gateway, billing, or production account provisioning is included in this handoff.

## 6. Frontend transition plan

The frontend repository is to be rebuilt from a clean architecture rather than inheriting the legacy UI hierarchy.

Early frontend work may use synthetic mock data for visual and interaction design. Mock data must live behind replaceable repositories/data adapters, not inside page components. The same UI contracts should later be backed by real API clients.

Before real authentication and workflow integration, reconcile:
- API request/response contracts.
- Tenant selection and tenant context.
- Role/permission-aware navigation and actions.
- Loading, empty, error, and validation behavior.
- Device-aware experiences: desktop operational workspace, tablet workflow workspace, and mobile task-oriented experience.

Do not present mock authorization or mock tenant isolation as real security.

## 7. Resume checklist

1. Confirm the current `main` commit and required CI status.
2. Read this handoff and `docs/roadmap/backend-improvement-roadmap.md`.
3. Read the platform-control-plane and tenant-isolation review documents.
4. Prioritize tenant MFA orchestration and support-grant enforcement design.
5. Complete MySQL tenant-isolation verification for nested/bulk IDs and concurrent transitions.
6. Design separate controlled provisioning for the first Platform Administrator.
7. Only then perform the approved first tenant/owner bootstrap in a verified development database.

---
This document records the source and CI checkpoint only. It is not a penetration-test report, compliance attestation, or production-readiness approval.

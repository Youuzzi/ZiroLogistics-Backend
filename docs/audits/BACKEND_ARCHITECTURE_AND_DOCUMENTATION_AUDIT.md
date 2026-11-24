# Backend Architecture and Documentation Audit

**Status:** Initial repository baseline completed; domain-by-domain verification remains in progress.
**Audited branch:** `main`
**Audited commit:** `90bc354c91c273a416a95352c3c242548a0c817e`
**Audit date:** 2026-11-25
**Scope:** Repository inventory, documentation naming and status, WMS migrations, authorization/tenant administration entry points, test inventory, and CI baseline.

## 1. Executive summary

The repository has a substantial WMS backend foundation, but its platform-administration and tenant-authorization contracts are not yet internally consistent. This is not a conclusion that the WMS core is immature; it is a focused finding that the control-plane identity model, initial-owner permissions, and the documentation lifecycle need additional design and verification before the first tenant is bootstrapped.

The audit is intentionally evidence-led. An item is marked implemented only when supported by source code, migration, and/or a test that exercises the relevant behavior. A design document by itself is not implementation evidence.

## 2. Repository inventory at the audited commit

The recursive Git tree at `90bc354c91c273a416a95352c3c242548a0c817e` contained:

| Area | Inventory |
|---|---:|
| Tracked files | 356 |
| Documentation files under `docs/` | 57 |
| WMS Flyway migrations | 27 (V1–V27) |
| Test source files | 101 |
| WMS Java source files | 148 |
| Java source files outside the WMS package | 0 |

The repository contains separate WMS modules for identity, authorization, tenancy, warehouse, inbound, putaway, inventory, outbound, picking, packing, shipping, transfer, stock count, adjustment, replenishment, reporting, security, and bootstrap.

The test inventory includes MySQL/Testcontainers integration coverage, HTTP authorization-boundary tests, tenant-isolation tests, migration tests, bootstrap tests, and role-management tests. File counts do not establish test quality or complete behavioral coverage; individual assertions still require review.

## 3. Documentation inventory and naming

There are 57 documentation files in `docs/`. Nineteen filenames contain stage-oriented naming such as `STAGE_10`, `STAGE_13`, or `STAGE_16_5`.

Examples include:
- `docs/api/WMS_V1_API_REFERENCE_STAGE_13.md`
- `docs/architecture/WMS_INVENTORY_CONTROL_STAGE_10_ARCHITECTURE.md`
- `docs/architecture/WMS_TRANSACTION_CONCURRENCY_INTEGRITY_STAGE_11_ARCHITECTURE.md`
- `docs/audits/WMS_STAGE_16_5_SECURITY_VERIFICATION.md`
- `docs/testing/WMS_V1_CONTROLLER_HTTP_CONTRACT_MATRIX_STAGE_13.md`

**Finding:** Stage names are useful as historical delivery metadata but are poor permanent document names. The target convention should describe the document's enduring function and domain. Rename work must be performed with a repository-wide reference update (README, docs links, workflow references, and internal Markdown links) to avoid broken navigation. Do not rename files blindly or remove historical evidence.

Recommended convention:
- `PLATFORM_ADMINISTRATION_AND_OPERATIONS.md`
- `TENANT_LIFECYCLE_AND_PROVISIONING.md`
- `ROLE_BASED_ACCESS_CONTROL.md`
- `WMS_PERMISSION_CATALOG.md`
- `WMS_ROLE_PERMISSION_MATRIX.md`
- `TENANT_ISOLATION_AND_AUTHORIZATION.md`
- `TENANT_LOCALE_AND_CURRENCY_POLICY.md`
- `SAAS_ENTITLEMENT_AND_FEATURE_ACCESS.md`
- `SUBSCRIPTION_LIFECYCLE.md`
- `BILLING_AND_INVOICING_ARCHITECTURE.md`

These are naming candidates, not a mandate to create duplicate documents. Consolidation and canonical ownership must be decided before renaming.

## 4. Verified authorization and tenant-administration findings

### 4.1 Platform operator identity is not implemented by the bootstrap service

The current `WmsPlatformBootstrapService` creates:
- one tenant;
- one global user identity;
- one active tenant membership;
- one tenant-scoped system role `TENANT_OWNER`;
- two role grants: `WMS_IDENTITY_MEMBERSHIP_INVITE` and `WMS_TENANT_PROFILE_UPDATE`;
- one completed bootstrap marker.

It does not create a separate platform-administrator identity, platform role, platform permission catalogue, or platform authentication boundary. Its class documentation explicitly states that it creates a tenant owner, not a global superuser.

The tenant lifecycle and provisioning flows can record an actor type of `PLATFORM_OPERATOR` and an `operator_reference` string in tenant audit events. This is audit attribution, not proof of an authenticated platform-operator identity or authorization model.

The bootstrap documentation currently says the operation atomically creates a "platform operator" as well as the tenant owner. That statement does not match the inspected bootstrap implementation and must be corrected or the implementation must be deliberately extended after the platform-operator architecture is approved.

### 4.2 Bootstrap and repeatable provisioning grant different owner permissions

The bootstrap service contains a two-entry `OWNER_PERMISSIONS` allowlist:
- `WMS_IDENTITY_MEMBERSHIP_INVITE`
- `WMS_TENANT_PROFILE_UPDATE`

The repeatable `WmsTenantProvisioningService` contains a larger explicit list covering tenant administration, role management, warehouse/location, item/catalogue, lot/serial, inventory, and reporting capabilities. The list is not the same as the bootstrap list.

The tenant organization document says repeatable provisioning grants only invitation/profile-update permissions. That statement is stale relative to the inspected provisioning service.

### 4.3 Migration backfills are not a substitute for provisioning grants

Migrations V9–V26 add permission catalogue entries and backfill grants to `TENANT_OWNER` roles that exist when each migration executes. The controlled bootstrap and repeatable provisioning commands run against a database whose migrations have already been applied. A tenant/role created after those migrations cannot rely on an earlier migration backfill to receive those permissions.

Therefore the owner permission set must be defined once as an explicit, reviewed policy and applied consistently to:
- first-install bootstrap;
- repeatable tenant provisioning;
- existing-tenant upgrade/backfill behavior where required;
- the documented role-permission matrix and tests.

The current divergence is a high-priority correctness gap to resolve before first-tenant bootstrap. Do not simply copy the provisioning list into bootstrap: the final list must first be reviewed for operational risk and separation of duties.

### 4.4 Role management has a narrower delegation allowlist

`WmsRoleManagementService` has a server-side `DELEGABLE_PERMISSIONS` set. It is narrower than the total permission catalogue and excludes sensitive operational actions. This is directionally appropriate for preventing privilege escalation, but the delegation policy needs to be documented and tested against the final role matrix.

The design document `WMS_ROLE_PERMISSION_MANAGEMENT_DESIGN.md` still labels several role-management permission codes as proposals that are not yet seeded. Migrations and source code show that role-management permissions and management service functionality have since been implemented. The document status and sections need to be updated to distinguish implemented behavior from remaining policy decisions.

## 5. Current tenant authorization boundary

The inspected `WmsMembershipAccessService` resolves effective access using authenticated user public ID and selected membership public ID, then builds permissions from persisted access rows. The tenant profile service checks that resolved tenant/membership identity matches the authenticated principal and requires the persisted `WMS_TENANT_PROFILE_UPDATE` permission.

The repository also contains tests for cross-tenant warehouse reads, reporting isolation, role management, tenant profile authorization, and several HTTP authorization boundaries.

These are positive implementation signals, but this inventory is not a complete tenant-isolation certification. The next security audit must trace every controller/use case/repository query and verify negative cross-tenant cases, including indirect object references, nested resources, background jobs, and administrative commands.

## 6. SaaS entitlement and billing

The repository search performed for this baseline did not locate implementation artifacts for subscription, billing, invoice, plan entitlement, or feature-access enforcement. This is a scoped search result, not proof that no related concept exists anywhere in unsearched external material.

The target architecture should document entitlement independently from RBAC:
- RBAC answers whether a user may perform an action.
- Entitlement answers whether the tenant's plan or contract includes the capability.
- Effective access requires both checks, plus tenant/resource scope and lifecycle status.

Subscription lifecycle, invoices, payment processing, reconciliation, trial, grace period, suspension, and usage metering can remain documented future capabilities. They should not be implemented as a payment gateway in this workstream.

## 7. Locale and time policy: audit still required

The tenant schema and tenant provisioning/profile services carry timezone and default-currency fields. The profile service validates timezone and currency using Java time/currency APIs. Bootstrap uses `ZoneId` validation and a three-letter currency format check; repeatable provisioning and profile update use stronger currency validation.

The inspected SQL uses `UTC_TIMESTAMP(6)` for several identity, membership, audit, and provisioning timestamps. This is positive evidence but not a complete audit of every timestamp source, JDBC/Hibernate conversion, report boundary, or tenant-local date calculation.

A dedicated audit must establish one canonical rule: persist instants in UTC, interpret and display tenant-local business dates/times using the tenant's IANA timezone, and use ISO-4217 currency codes with explicit monetary precision/rounding rules. Validate the rule across all operational modules before claiming full compliance.

## 8. CI baseline

At the audited commit, the latest post-merge Backend CI run was successful:
- Workflow: Backend CI
- Run: 45444820242
- Commit: `90bc354c91c273a416a95352c3c242548a0c817e`
- Conclusion: success

This confirms the recorded workflow run for that commit. It does not certify changes made after that commit or replace required PR checks for upcoming work.

## 9. Prioritized findings and next actions

| Priority | Finding | Required action |
|---|---|---|
| Critical before bootstrap | No distinct authenticated platform-administrator identity is created by bootstrap; operator references are audit labels | Design control-plane identity, authentication, permissions, break-glass, and audit boundary |
| Critical before bootstrap | Bootstrap owner gets two permissions while repeatable provisioning gets a different larger set | Approve a canonical owner baseline and apply it consistently with additive migration/tests |
| High | Tenant organization and bootstrap docs conflict with current provisioning/bootstrap behavior | Correct status and claims after implementation contract is approved |
| High | Role-management design still describes implemented permissions as proposals | Update design status, actual API/permission contracts, delegation policy, and test evidence |
| High | Tenant isolation is covered in several tests but not yet exhaustively certified across all modules | Perform controller-to-query and negative cross-tenant audit |
| Medium | Locale fields exist, but global UTC/timezone/currency policy is not proven end-to-end | Trace timestamp and money handling across schema, Java, SQL, APIs, and reports |
| Medium | No entitlement/subscription/billing implementation was found by scoped repository search | Create architecture and lifecycle documents; defer payment integration |
| Medium | Nineteen docs use stage-oriented filenames | Establish canonical document map and perform safe rename/reference updates |

## 10. Safety and change boundaries

- No database, migration history, local Docker resource, credential, or bootstrap state was modified by this audit.
- No tenant was created.
- Frozen schema baselines and historical audit evidence must remain intact.
- Any schema changes must be additive and supported by migration tests.
- The first tenant bootstrap remains blocked until platform identity, owner permission policy, tenant isolation, locale policy, and bootstrap inputs are reviewed and explicitly approved.

## 11. Evidence reviewed

Primary repository evidence:
- `src/main/java/com/zirocraft/zirologistics/wms/bootstrap/WmsPlatformBootstrapService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/bootstrap/WmsBootstrapCommandRunner.java`
- `src/main/java/com/zirocraft/zirologistics/wms/tenancy/application/WmsTenantProvisioningService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/tenancy/application/WmsTenantProfileService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/tenancy/application/WmsTenantLifecycleService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsMembershipAccessService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsRoleManagementService.java`
- `src/main/resources/db/wms-migration/V1__create_tenant_wms_schema.sql`
- `src/main/resources/db/wms-migration/V5__seed_wms_identity_invitation_permission.sql` through `V26__add_wms_operational_reporting_permission.sql`
- `src/test/java/com/zirocraft/zirologistics/wms/bootstrap/WmsPlatformBootstrapMySqlIntegrationTest.java`
- `src/test/java/com/zirocraft/zirologistics/wms/tenancy/WmsTenantProvisioningMySqlIntegrationTest.java`
- `src/test/java/com/zirocraft/zirologistics/wms/authorization/WmsRoleManagementServiceTest.java`
- `docs/architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md`
- `docs/architecture/WMS_TENANT_ORGANIZATION_MANAGEMENT.md`
- `docs/architecture/WMS_ROLE_PERMISSION_MANAGEMENT_DESIGN.md`
- `docs/architecture/WMS_TENANT_CONTEXT_AND_AUTHORIZATION_RESOLUTION.md`

This document is the initial baseline, not the final sign-off for the full eight-part workstream. Each subsequent audit section must be updated with exact source, migration, and test evidence as it is completed.

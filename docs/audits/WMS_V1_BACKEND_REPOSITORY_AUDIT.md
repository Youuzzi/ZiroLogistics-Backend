# WMS V1 Backend Repository Audit — Historical Baseline

> **Historical document:** This was the initial repository audit before the WMS runtime and Stage 1–13 implementation. Its old recommendations about retiring branches, legacy runtime defaults, and pending WMS implementation are superseded by the current repository state and [Stage 13 WP11 Final API and Repository Audit](WMS_STAGE_13_FINAL_API_AND_REPOSITORY_AUDIT.md). Keep this file as historical evidence; do not use it as current operational instructions.

- **Status:** Historical initial audit; not the current implementation baseline.
- **Original audited commit:** `b9386b1107b6bcdd2b16d5bf91d8c733e7e6340b`
- **Current implementation baseline:** See the Stage 13 WP11 audit linked above.
- **Target:** Tenant-based ZiroLogistics WMS V1.
- **Authoritative schema:** `docs/database/source/zirowms_v1_schema.sql`
- **Schema migration:** `classpath:db/wms-migration`

## Historical assessment

The original repository contained a legacy single-organization warehouse application alongside the frozen tenant-based WMS V1 schema. The original audit correctly warned that the legacy entity model was not the WMS V1 persistence model.

The WMS runtime is now implemented as an isolated application under `com.zirocraft.zirologistics.wms`, with dedicated component/entity/repository scanning and an explicit Maven packaging profile. See [WMS Runtime Boundary](../runtime/WMS_RUNTIME_BOUNDARY.md).

## Historical architecture recommendations

The original audit proposed a package-by-feature modular monolith. This remains the direction for the dedicated Backend Structural Audit & Refactoring, but it must be applied incrementally and only where it improves ownership and testability. Do not create empty boilerplate packages or blindly generate entities for every table.

## Historical branch recommendations — superseded

The original audit suggested retiring branches after local working-tree checks. That recommendation is no longer current. Branch cleanup is deferred until the overall project reaches Stage 18 and a final audit confirms every PR and commit is accounted for. Do not delete branches as part of this historical audit.

## Non-negotiable safety rules retained

- The frozen WMS V1 DDL remains the schema authority.
- Never point the legacy runtime at the WMS database.
- Never run schema-changing commands against `zirowms_v1` or `zirologistics_db`.
- All migration/runtime integration tests use disposable MySQL 8.4 Testcontainers databases.
- Never trust a tenant ID supplied by the client as authorization.
- Tenant isolation must be enforced server-side and in persistence queries.
- Keep inventory ledger posting transactional and immutable.
- Preserve decimal precision from the actual schema.
- Do not create all schema entities blindly; implement domain slices and test each one.
- Mark findings as verified only when backed by an executed test or reproducible runtime evidence.

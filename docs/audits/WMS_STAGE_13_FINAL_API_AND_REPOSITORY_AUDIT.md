# Stage 13 WP11 — Final API and Repository Audit

- **Status:** Implementation submitted for PR review; WP11 CI evidence pending.
- **Audit baseline:** `a160b3ed0a52a9b9d98a8ac49b5e452af9e980ce`
- **Work branch:** `chore/stage-13-wp11-wms-runtime-docs-infrastructure`

## 1. Verified baseline

Main CI run #645 verified the preceding WP10 merge:
- 352 tests;
- 0 failures;
- 0 errors;
- 2 skipped;
- BUILD SUCCESS;
- MySQL Testcontainers reported MySQL 8.4.13.

This is baseline evidence, not evidence for the changes in WP11.

## 2. API and security contract reconciliation

The Stage 13 API reference already documented WP2 behavior: JWT enables operational WMS routes; the public-auth switch controls only the exact anonymous POST allowlist. The older authentication HTTP contract incorrectly described both switches as required for all WMS endpoints. This WP corrects that mismatch.

Operational routes remain registered and authenticated when JWT is enabled, regardless of the anonymous-auth switch. Invitation issuance and existing-account invitation acceptance remain authenticated. JWT-disabled mode remains deny-all. The API v1 prefix and response compatibility rules are unchanged.

## 3. Runtime and packaging boundary

- WMS entry point: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`.
- WMS component/entity/repository scanning remains isolated to the WMS namespace.
- WMS is the default executable JAR entry point; explicit Maven profile `wms-runtime` selects it for Docker/CI packaging, while the legacy application is opt-in only through temporary profile `legacy-runtime`.
- Root Dockerfile packages the WMS artifact.
- Root Compose provisions a dedicated MySQL 8.4 WMS database and volume, without publishing MySQL to the host.
- Legacy root entry point remains transitional source/test material, not the WMS container entry point.

## 4. Legacy source disposition

This WP does not blindly move or delete legacy classes. Existing WMS runtime boundary tests assert representative legacy controllers, services, and repositories are absent from the WMS context. Legacy technical-layer packages are not imported by WMS and remain compiled/exercised by the current regression suite.

- **Not a WMS dependency:** legacy controllers, entities, repositories, services, filters, and legacy application configuration are not migrated into WMS just to keep them.
- **Temporary retention:** legacy source remains until the dedicated Backend Structural Audit & Refactoring maps source dependencies to legacy tests, replaces or retires legacy-only tests, and proves no WMS module depends on old packages.
- **Required follow-up:** migrate only behavior proven reusable and required by WMS into WMS-owned modules; delete legacy-only code and tests after dependencies and regression coverage are resolved.
- **No legacy database migration:** this WP does not connect to or mutate legacy/reference databases.

This is a source/runtime boundary classification, not a claim that every legacy file has already been deleted.

## 5. Database and migration safety

WMS connection settings use `WMS_DB_URL`, `WMS_DB_USERNAME`, and `WMS_DB_PASSWORD`. Flyway is controlled independently by `WMS_FLYWAY_ENABLED` and defaults to false in WMS properties. Compose requires an explicit Flyway setting for its isolated WMS database. Migration location remains `classpath:db/wms-migration`. Frozen V1–V26 and reference DDL are unchanged; future changes remain additive V27+.

## 6. Secret and environment-file hygiene

- Committed `.env.example` contains local placeholders and non-secret disabled-mode identifiers only.
- `.gitignore` excludes `.env` and `.env.*` while explicitly allowing `.env.example`.
- Docker build context excludes local environment files and private key-store/certificate extensions.
- Compose has no hard-coded fallback database/root passwords.
- JWT and anonymous auth endpoints are disabled by default.

## 7. Maven, CI, and packaging

Maven Wrapper is pinned to 3.9.14. Flyway core and MySQL module remain aligned at 11.20.3. The old POM comment incorrectly implied this Flyway override removed MySQL integer display-width warnings; it is corrected to state that those warnings originate from MySQL 8.4 DDL deprecation in frozen V1 statements.

CI continues to run full regression for main pushes/manual runs and PRs changing code, tests, build, Docker, Compose, workflow, or environment files. The full CI command packages with `-Pwms-runtime`, verifies the executable JAR manifest Start-Class, and validates Compose syntax against `.env.example`. Documentation-only PRs retain diff-check-only optimization.

## 8. MySQL 8.4 warning disposition

The previous audit counted 513 integer display-width deprecation warning instances: 510 from repeated execution of 15 WMS V1 `TINYINT(1)` declarations and 3 from legacy integer-width declarations. This is a server DDL deprecation warning, not a test failure or Flyway compatibility error.

Frozen migrations V1–V26 are not edited and Flyway checksums are not changed merely to silence warnings. The future-migration policy test remains in place to prevent new integer display-width declarations. The warning remains documented for future schema/version planning.

## 9. Verification

Pending:
- WP11 PR full regression CI;
- WMS Maven profile executable JAR manifest assertion;
- Compose configuration validation;
- post-merge Main CI on exact merge commit.

No WP11 result is labelled VERIFIED until the corresponding workflow evidence is available.

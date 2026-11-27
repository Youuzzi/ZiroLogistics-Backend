# Backend Quality and Security Baseline

**Snapshot date:** 2026-11-27  
**Repository checkpoint:** `148177826c5eebd56b6134851e5d901809549448`  
**Status:** Security and QA work has produced substantial implementation and CI evidence, but verification remains incomplete. This document is a baseline and evidence boundary, not a production-readiness approval.

## Repository inventory

Counts were taken from the repository tree at the checkpoint:

| Area | Current inventory |
|---|---:|
| Production Java source files | 194 |
| Java test source files | 136 |
| WMS Flyway migrations | 32 (V1–V32) |
| Spring Boot | 3.5.16 |
| Java | 21 |
| Database target | MySQL 8.4 |

Test-file counts do not establish test-method coverage or requirements coverage. JaCoCo is configured in Maven; current percentage results must be taken from a fresh report for the exact commit rather than copied from historical baselines.

## Automated checks in the repository

- Backend CI: Java 21 Maven regression/package checks, Compose validation, WMS runtime packaging verification, and Docker image build.
- CodeQL: Java static analysis workflow.
- OSV: dependency vulnerability scanning in Backend CI.
- Authenticated WMS API DAST: isolated scan workflow with a deliberately limited route scope.
- k6: read-only performance baseline assets/workflow.
- JUnit 5 and MySQL 8.4 Testcontainers: unit, application, and database integration tests.
- JaCoCo: Maven coverage report generation.

A successful workflow proves only that the checks included in that workflow passed on the tested commit. It does not prove every route, mutation, worker, tenant boundary, or production operational control is covered.

## Security and integrity evidence

### Verified implementation slices

- Tenant access-token credential-version validation is present.
- Tenant MFA reauthentication grant persistence and dedicated MFA throttling are merged.
- Tenant MFA factor lifecycle persistence is merged: pending/active/revoked lifecycle, replay watermark seeding, and encrypted-seed erasure on revocation.
- Platform and tenant JWT trust domains have separate decoder/key/issuer/audience configuration and a startup collision guard.
- Real RSA cross-signature tests prove that the tenant decoder rejects platform-signed tokens and the platform decoder rejects tenant-signed tokens.
- Inventory balance and serial mutation final-write predicates include tenant scope.
- Source reviews of selected warehouse, inventory, inbound, transfer, replenishment, stock count, shipping, picking, and packing paths found no concrete cross-tenant vulnerability in the reviewed slices.

### Evidence limitations

- Static source review is not MySQL runtime verification.
- Tenant-isolation tests are not yet uniformly comprehensive for every controller, nested/bulk identifier, mutation, and concurrent transition.
- DAST coverage is limited to the routes and methods represented by its current scan contract; it is not a full application penetration test.
- k6 evidence is not a production capacity or end-to-end workflow benchmark.
- CI does not establish backup/restore, monitoring, incident response, key rotation, or production deployment readiness.

## Important open security boundaries

1. Tenant MFA enrollment, verification, step-up, recovery, and transactional proof consumption are not fully orchestrated. MFA remains disabled by default.
2. Platform support-grant governance exists, but no runtime consumer/enforcement path was identified in the reviewed source slice. Approval alone must not grant access.
3. The existing tenant bootstrap creates a Tenant Owner and tenant records, not a Platform Administrator.
4. Platform HTTP route coverage and full operator permission enforcement require additional implementation and tests.
5. Full route-to-query tenant-isolation trace and MySQL negative/concurrency evidence remain open.
6. Production secrets, distinct deployed key material, key rotation, operational monitoring, backup/restore, and recovery runbooks remain deployment responsibilities.

## Evidence labels

- **MySQL integration verified:** behavior was exercised against disposable MySQL 8.4.
- **CI verified:** named checks passed on the exact PR head/commit.
- **Source reviewed:** inspected implementation; no runtime guarantee implied.
- **Partially verified:** evidence covers selected paths only.
- **Unverified:** no sufficient evidence captured.
- **Proposed:** planned, not implemented.

## Engineering rules

- Preserve regression coverage; never weaken tests to make CI pass.
- Use disposable MySQL/Testcontainers and synthetic data.
- Do not use `zirologistics_db`, `zirowms_v1`, production data, or developer-owned databases for QA.
- Keep applied Flyway migrations immutable; future changes are additive from V33.
- Record exact commit and workflow evidence.
- Do not declare production readiness from green CI or source review alone.

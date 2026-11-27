# Focused WMS Security Verification

> **Historical verification evidence:** This report records assertions and execution from its named commit and Backend CI run. It is not the current security status or a certification of all routes. Use the [Security and QA Baseline](security-qa-baseline.md), [Security Coverage Inventory](security-coverage-inventory-2026-11-27.md), and current CI results for the latest checkpoint.

**Evidence commit:** `1c14922614db04c4e3a78ceb5eafa0845ba988ca`
**Verification run:** Backend CI #690, attempt 2
**Run URL:** https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44742151637
**Run result:** SUCCESS
**Scope:** Existing WMS security and tenant-boundary tests inspected at assertion level and executed as part of the full Maven regression run.
**Overall assessment:** PARTIALLY VERIFIED. The controls listed below have executable evidence; route-wide cross-tenant authorization coverage and several deployment-dependent controls remain open.

## 1. Reproducible execution evidence

CI executed:

```bash
./mvnw --batch-mode --no-transfer-progress -Pwms-runtime clean verify
```

Surefire summary from the CI log:

- Tests run: **334**
- Failures: **0**
- Errors: **0**
- Skipped: **2**
- Maven result: **BUILD SUCCESS**

The two skipped tests are `WmsArgon2idBenchmarkTest` and `WmsArgon2idConcurrencyTest`. They are not counted as executed performance evidence. CI also validated the WMS Compose configuration, verified the packaged WMS entry point, uploaded the JaCoCo report, and built the WMS Docker image.

This is regression evidence for the recorded commit, not proof that every security property or production deployment scenario is covered.

## 2. Controls verified by inspected assertions and CI execution

| Control | Repository evidence | What the tests actually assert | Assessment |
|---|---|---|---|
| Tenant profile ownership | `src/test/java/com/zirocraft/zirologistics/wms/tenancy/WmsTenantProfileMySqlIntegrationTest.java` | Rejects a forged principal whose tenant and membership do not match persisted membership; rejects update without persisted permission; checks optimistic-version conflict and audit behavior. MySQL 8.4 Testcontainers fixture. | VERIFIED for this service/profile path |
| Role permission and tenant match | `src/test/java/com/zirocraft/zirologistics/wms/authorization/WmsRoleManagementServiceTest.java` | Denies role reads without permission and when principal tenant differs from persisted membership; ensures JDBC is not called after denial; bounds pagination. | VERIFIED for tested service paths |
| JWT profile separation | `src/test/java/com/zirocraft/zirologistics/wms/security/config/WmsJwtConfigurationTest.java` | Rejects selection token at access decoder and access token at selection decoder; checks token profile/audience separation and disabled-by-default bean configuration. | VERIFIED for tested decoder rules |
| Current membership resolution | `src/test/java/com/zirocraft/zirologistics/wms/security/application/WmsJwtAuthenticationConverterTest.java` | Rejects missing subject/membership, inactive or no-longer-authorized membership, and a resolved identity that does not match token subject/membership. | VERIFIED for converter cases |
| HTTP authentication boundary | `src/test/java/com/zirocraft/zirologistics/wms/security/config/WmsHttpSecurityEnabledTest.java`; `WmsHttpSecurityDisabledTest.java`; `WmsHttpSecurityAuthEndpointsDisabledTest.java` | Denies requests without bearer token, restricts public routes to explicitly permitted POST endpoints when enabled, and exercises the JWT-disabled deny-all configuration. | VERIFIED for tested routes/configurations |
| CORS allowlist | `src/test/java/com/zirocraft/zirologistics/wms/security/config/WmsCorsSecurityIntegrationTest.java` | Accepts configured-origin preflight, rejects unconfigured origin, confirms CORS does not bypass authentication, and rejects wildcard origin configuration. | VERIFIED for tested HTTP cases |
| Trusted proxy resolution | `src/test/java/com/zirocraft/zirologistics/wms/security/throttling/application/WmsClientAddressResolverTest.java` | Ignores forwarding data from untrusted peers, walks trusted proxy chain from right to left, stops at untrusted hop, falls back for malformed/oversized chains, supports IPv6, and rejects invalid configuration/address. | VERIFIED for resolver cases |
| Login throttling | `src/test/java/com/zirocraft/zirologistics/wms/security/throttling/application/WmsLoginThrottleServiceTest.java`; `WmsThrottleStorageContractTest.java`; `src/test/java/com/zirocraft/zirologistics/wms/security/throttling/infrastructure/persistence/MySqlWmsThrottleStorageAdapterIntegrationTest.java` | Exercises source limit, account cooldown, reset behavior, fail-closed storage errors, single terminal attempt outcome, and MySQL storage behavior. | VERIFIED for tested policy/storage cases |
| Error disclosure | `src/test/java/com/zirocraft/zirologistics/wms/security/authentication/http/WmsApiErrorContractTest.java` | Confirms stable error contracts and that unexpected internal DB connection details are not returned in the public error message. | VERIFIED for tested handler cases |
| Credential authentication | `WmsCredentialAuthenticationServiceTest.java`, `WmsCredentialAuthenticationMySqlIntegrationTest.java`, `WmsUnknownUserPasswordVerifierTest.java`, `WmsAuthenticationControllerHttpTest.java` under the security authentication test packages | Relevant service, persistence, unknown-user verification, and HTTP authentication cases all executed successfully in this CI run. | VERIFIED for the executed test cases |

## 3. Source-level observations

The inspected runtime configuration in `src/main/java/com/zirocraft/zirologistics/wms/security/config/WmsSecurityConfiguration.java` establishes:

- A WMS-only servlet security chain.
- Stateless request handling.
- Form login, HTTP Basic, logout, and request cache disabled.
- An explicit JWT-enabled chain with authentication required by default.
- A JWT-disabled chain that denies all requests by default.
- Public authentication endpoints only when the explicit `wms.security.http.auth-endpoints-enabled` property is enabled.

The inspected `WmsCorsConfiguration.java` uses exact configured origins, enables credentials only with that explicit-origin list, and rejects wildcard origins.

These are source observations; the test assertions above provide runtime evidence only for the cases named in the matrix.

## 4. Residual verification work

The following are still open review items, not confirmed vulnerabilities:

1. Build a route-level cross-tenant substitution matrix for all tenant-scoped controllers, including list/search and nested resource routes. The current evidence proves selected tenant profile and role-management paths, not every endpoint.
2. Inspect and test authorization revocation behavior across requests and concurrent permission changes.
3. Review secrets/configuration exposure in the complete repository and deployment configuration. This report does not claim a complete secret scan.
4. Verify trusted-proxy CIDRs and forwarded-header behavior in the eventual deployment topology; unit tests do not prove the production proxy chain is configured correctly.
5. Review the two skipped Argon2 benchmark/concurrency tests separately before using them as capacity evidence.
6. DAST, authenticated ZAP scanning, and production-like network behavior belong to WP16.6 and are not covered here.

No new executable test was added in this WP because the inspected existing tests already exercised the specific cases above. The remaining route-wide gaps must be addressed with targeted tests only after controller/service ownership is mapped.

## 5. Verification disposition

**PARTIALLY VERIFIED — evidence captured; not yet closed.**

The recorded CI run proves that the inspected security regression tests pass on commit `1c14922614db04c4e3a78ceb5eafa0845ba988ca`. It does not prove full tenant isolation across every route, production proxy correctness, complete secret hygiene, or DAST coverage. Keep those as explicit follow-up items for the next QA work packages and final closure.

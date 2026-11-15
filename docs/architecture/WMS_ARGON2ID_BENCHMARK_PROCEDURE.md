# WMS Argon2id Benchmark Procedure

**Status:** Measurement harness added; no benchmark has been run and no performance result or login threshold is approved.  
**Policy under test:** WMS Argon2id encoder configured in `WmsPasswordEncoderConfiguration` (19 MiB memory, 2 iterations, parallelism 1, 16-byte salt, 32-byte output).

## 1. Purpose

Measure the actual WMS password encoder on a representative machine before selecting login throttling thresholds, cooldowns, or expected authentication capacity.

The harness measures:
- password hash generation (`encode`);
- password verification (`matches`);
- a sequential encode + verify pair.

It performs 5 warm-up rounds and 30 measured samples for each operation, then prints minimum, median, p95, and maximum latency. It also prints Java, JVM, operating system, processor count, and maximum heap so results are not detached from their execution environment.

## 2. Run locally

Use a clean checkout of the feature branch and Java 21. From the backend repository root:

```powershell
.`\mvnw.cmd -Dtest=WmsArgon2idBenchmarkTest -Dwms.argon2.benchmark=true test
```

The benchmark is opt-in. Normal `mvn test` runs skip it, so CI does not become timing-flaky and does not accidentally publish machine-specific latency as a production baseline.

Run it at least three times on the intended VPS class or a machine with comparable CPU and memory limits. Record each output together with the machine size, container CPU/memory limits (if applicable), and whether other workloads were active. Prefer a quiet machine; do not infer production capacity from a developer laptop alone.

## 3. Interpretation

- Use the verify p50/p95 as the primary password-check cost relevant to a login request.
- Encode is relevant to future password creation/reset flows, but is not part of ordinary credential verification.
- Encode + verify is a diagnostic pair, not a claim that every login performs both.
- Memory is a concurrency constraint as well as a latency parameter. At 19 MiB per Argon2 operation, concurrent operations can consume substantial memory; estimate peak concurrency with JVM/container overhead and other application workloads included.
- Repeat measurements under representative concurrent load before setting request limits. A sequential microbenchmark does not measure queueing, database latency, network latency, or full endpoint throughput.

Do not choose account/source bucket thresholds directly from a single latency number. Combine measured password verification cost with expected legitimate traffic, NAT/shared-IP behavior, abuse scenarios, service capacity, and operational recovery requirements.

## 4. Safety and scope

- The benchmark uses a fixed synthetic test password only; it does not read user accounts or database rows.
- It does not connect to MySQL, Testcontainers, Redis, or either user database.
- It does not change Argon2id parameters or production configuration.
- It is not a JMH-grade statistically rigorous benchmark. For capacity planning, follow with a controlled load test on the target deployment class.
- Do not commit benchmark output containing host-specific or sensitive infrastructure details unless intentionally approved.

## 5. Required evidence before policy approval

Record:
1. Java/JVM version and CPU/memory limits.
2. At least three sequential benchmark runs and their p50/p95 values.
3. A representative concurrent verification load test.
4. Memory headroom at the expected maximum concurrent authentication workload.
5. The resulting rationale for account/source thresholds, cooldown duration, and fail-closed capacity behavior.

Until that evidence is reviewed, all numeric login-throttling thresholds remain unapproved.

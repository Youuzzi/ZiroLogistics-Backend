# WMS Argon2id Benchmark Procedure

**Status:** Sequential and bounded concurrency harnesses are implemented; local measurements have been collected on one Windows 11 development machine. No production capacity or login-throttling threshold is approved.  
**Policy under test:** WMS Argon2id encoder configured in `WmsPasswordEncoderConfiguration` (19 MiB memory, 2 iterations, parallelism 1, 16-byte salt, 32-byte output).

## 1. Purpose

Measure the actual WMS password encoder on a representative machine before selecting login throttling thresholds, cooldowns, or expected authentication capacity.

The sequential harness measures:
- password hash generation (`encode`);
- password verification (`matches`);
- a sequential encode + verify pair.

It performs 5 warm-up rounds and 30 measured samples for each operation, then prints minimum, median, p95, and maximum latency.

The concurrency harness measures password verification with 1, 2, and 4 simultaneous workers, each performing 10 operations after a synchronized start. It reports total batch time, aggregate throughput, and per-operation p50/p95/maximum latency. The levels are deliberately bounded to keep this local diagnostic safe and repeatable.

Both harnesses print Java, JVM, operating system, processor count, and maximum heap. They use only a synthetic password and do not access application data.

## 2. Run the sequential benchmark

Use Java 21 from the backend repository root:

```powershell
.\mvnw.cmd "-Dtest=WmsArgon2idBenchmarkTest" "-Dwms.argon2.benchmark=true" test
```

Run it at least three times on the intended VPS class or a machine with comparable CPU and memory limits. Record each output together with the machine size, container CPU/memory limits (if applicable), and whether other workloads were active. Prefer a quiet machine; do not infer production capacity from a developer laptop alone.

## 3. Run the bounded concurrency probe

After the sequential benchmark completes, run:

```powershell
.\mvnw.cmd "-Dtest=WmsArgon2idConcurrencyTest" "-Dwms.argon2.concurrency=true" test
```

The probe runs concurrency levels 1, 2, and 4, with 10 verifications per worker. It does not start an unbounded stress test and does not contact MySQL, Testcontainers, Redis, or either user database.

Run once initially. If the machine remains responsive and the output is stable, repeat once for comparison. Do not increase worker counts or operation counts without reviewing available memory and CPU limits first.

## 4. Interpretation

- Use verify p50/p95 as the primary password-check cost relevant to a login request.
- Encode is relevant to future password creation/reset flows, but is not part of ordinary credential verification.
- Encode + verify is a diagnostic pair, not a claim that every login performs both.
- Compare latency as concurrency increases. A rising p95 indicates queueing/contention or reduced per-operation CPU availability; throughput may rise more slowly than worker count.
- At 19 MiB per Argon2 operation, four concurrent operations imply at least about 76 MiB of Argon2 working memory, before JVM and application overhead. This is a rough parameter-based estimate, not a measured peak heap figure.
- The concurrency probe is a local microbenchmark, not a full HTTP load test. It does not measure database, network, proxy, servlet, or end-to-end request latency.

Do not choose account/source bucket thresholds directly from a single latency number. Combine measurements with expected legitimate traffic, NAT/shared-IP behavior, abuse scenarios, service capacity, and operational recovery requirements.

## 5. Safety and scope

- Both harnesses use a fixed synthetic test password only; they do not read user accounts or database rows.
- They do not connect to MySQL, Testcontainers, Redis, or either user database.
- They do not change Argon2id parameters or production configuration.
- They are opt-in and skipped during ordinary CI runs to avoid timing-flaky tests.
- They are diagnostic harnesses, not JMH-grade statistically rigorous benchmarks.
- Do not commit benchmark output containing host-specific or sensitive infrastructure details unless intentionally approved.

## 6. Required evidence before policy approval

Record:
1. Java/JVM version and CPU/memory limits.
2. At least three sequential benchmark runs and their p50/p95 values.
3. Bounded concurrent verification results at 1, 2, and 4 workers.
4. Memory headroom at the expected maximum concurrent authentication workload, measured on the target deployment class.
5. A representative HTTP-level load test before production capacity claims.
6. The resulting rationale for account/source thresholds, cooldown duration, and fail-closed capacity behavior.

Until that evidence is reviewed, all numeric login-throttling thresholds remain unapproved.

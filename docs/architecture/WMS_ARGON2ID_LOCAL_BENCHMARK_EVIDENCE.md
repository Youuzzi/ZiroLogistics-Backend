# WMS Argon2id Local Benchmark Evidence

**Status:** Local developer-machine evidence only; not a production capacity approval.  
**Date:** 2026-11-16 (Asia/Jakarta)  
**Scope:** Existing WMS Argon2id encoder configuration, synthetic password inputs only.  
**Database access:** None. No user credentials or database records were used.

## 1. Environment

Both benchmark harnesses were run on the same local machine:

- Java: 21.0.6
- VM: OpenJDK 64-Bit Server VM
- OS: Windows 11 / amd64
- Reported processors: 16
- Reported maximum heap: 4,008 MiB
- Argon2id: memory 19,456 KiB (19 MiB), iterations 2, parallelism 1
- Salt: 16 bytes
- Output: 32 bytes

The results describe this machine and this runtime only. They are not VPS measurements and must not be extrapolated directly to a production instance.

## 2. Sequential benchmark

Harness: `WmsArgon2idBenchmarkTest`  
Methodology: 5 warm-up rounds and 30 measured samples per operation. Three successful runs.

All values are milliseconds.

| Run | Operation | Min | Median | p95 | Max |
|---:|---|---:|---:|---:|---:|
| 1 | Encode | 20.30 | 23.88 | 33.25 | 38.71 |
| 1 | Verify | 20.48 | 23.51 | 31.44 | 31.60 |
| 1 | Encode + verify | 41.23 | 46.39 | 62.06 | 70.15 |
| 2 | Encode | 18.60 | 19.37 | 22.17 | 26.44 |
| 2 | Verify | 18.32 | 19.53 | 23.14 | 25.14 |
| 2 | Encode + verify | 37.60 | 39.00 | 44.78 | 47.31 |
| 3 | Encode | 19.58 | 22.78 | 27.49 | 29.96 |
| 3 | Verify | 19.71 | 22.68 | 29.17 | 30.76 |
| 3 | Encode + verify | 39.75 | 45.52 | 54.37 | 59.13 |

Each run completed with one test, zero failures, zero errors, and zero skipped tests.

## 3. Bounded concurrency probe

Harness: `WmsArgon2idConcurrencyTest`  
Methodology: 5 warm-up operations; synchronized worker start; 10 password verifications per worker; concurrency levels 1, 2, and 4. Two successful runs.

### Run 1

| Workers | Operations | Batch elapsed (ms) | Throughput (ops/s) | p50 (ms) | p95 (ms) | Max (ms) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 226.42 | 44.17 | 20.86 | 34.95 | 34.95 |
| 2 | 20 | 265.07 | 75.45 | 23.67 | 30.26 | 38.60 |
| 4 | 40 | 478.06 | 83.67 | 45.73 | 56.77 | 63.27 |

### Run 2

| Workers | Operations | Batch elapsed (ms) | Throughput (ops/s) | p50 (ms) | p95 (ms) | Max (ms) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 217.00 | 46.08 | 20.54 | 25.43 | 25.43 |
| 2 | 20 | 255.94 | 78.14 | 24.06 | 30.56 | 30.87 |
| 4 | 40 | 388.84 | 102.87 | 37.49 | 46.97 | 50.72 |

Each run completed with one test, zero failures, zero errors, and zero skipped tests.

## 4. Interpretation

- Sequential verification medians were approximately 19.53–23.51 ms across the three runs.
- In the concurrency probes, throughput increased from one to four workers in both runs.
- Four-worker p50 and p95 latency were higher than one-worker latency, consistent with contention/scheduling costs under concurrent memory-hard work.
- The second concurrency run was faster than the first at four workers. This variation is a reason to treat these numbers as a local baseline, not a capacity guarantee.
- The configured 19 MiB Argon2id parameters are retained. These measurements alone do not justify changing the password-hashing cost.

Argon2 memory use is a relevant capacity constraint: four simultaneous operations have a nominal Argon2 working-memory component of roughly 76 MiB (4 × 19 MiB), before JVM, application, native/provider, request, and database overhead. This is an estimate, not a measured process-memory figure.

## 5. What this evidence does not establish

These harnesses do not measure:

- HTTP request handling, servlet/container queues, or end-to-end login latency;
- simultaneous logins across multiple application instances;
- database-backed throttle contention or transaction behavior;
- real production CPU/memory limits, container quotas, or VPS performance;
- safe login thresholds, cooldown values, or a production requests-per-second limit;
- resistance to distributed password spraying, NAT/shared-IP behavior, or bot traffic.

No numeric throttle thresholds are approved by this evidence.

## 6. Next verification gates

Before enabling credential login:

1. Run the same harness on the intended VPS class or a representative deployment environment.
2. Run a bounded HTTP-level load test with the real authentication path after the throttle persistence adapter exists.
3. Test source/account bucket behavior, concurrency, transaction rollback, and database outage behavior using disposable MySQL 8.4 Testcontainers.
4. Review expected legitimate traffic, NAT/shared-office scenarios, and abuse cases before approving thresholds and cooldowns.

All integration tests must use disposable Testcontainers databases. Never run migrations or tests against `zirowms_v1` or `zirologistics_db`.

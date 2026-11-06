# Testing Evidence

## Current automated test evidence

The test source currently contains:

- `ZirologisticsApplicationTests.contextLoads()`
- Spring Boot application context startup under the `test` profile.
- H2 configured as the test database dependency.

This is a context smoke test. It can provide evidence that the application context starts with the test configuration.

## What this test does not prove

The current smoke test does not independently prove:

- Authentication and authorization behavior for every role.
- Warehouse, bin, item, inbound, outbound, or transfer endpoint behavior.
- Inventory balance correctness across transaction workflows.
- Concurrent stock mutation safety.
- Idempotent handling of repeated requests.
- Audit history correctness.
- Production MySQL compatibility.

## Recommended future test layers

| Test layer | Suggested evidence |
|---|---|
| Unit tests | Validation and business rules in services |
| Repository tests | Entity mappings and query behavior |
| MVC/security tests | Endpoint contracts, JWT authentication, role access |
| Integration tests | Realistic database-backed stock workflows |
| Concurrency tests | Competing stock updates and invariant preservation |
| Idempotency tests | Repeated request identifier does not duplicate a movement |

## Reporting rule

Only mark a behavior as verified after an automated test or reproducible manual test directly demonstrates it. Keep implementation observations separate from test evidence. Update this page when new tests are added and executed; do not report planned tests as completed evidence.

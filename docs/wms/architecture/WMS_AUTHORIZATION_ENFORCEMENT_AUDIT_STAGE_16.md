# Stage 16 — Authorization Enforcement Audit

## Scope and status

This is the evidence-backed first pass of the Stage 16 authorization enforcement audit. It records concrete code paths inspected and the remaining audit gates. It is not a claim that every controller, service, repository, and scheduled worker in the repository has already been exhaustively reviewed.

The audit rule is that HTTP authentication is only the outer boundary. Tenant permission checks, tenant-scoped data access, workflow state checks, separation-of-duties checks, and audit writes must remain enforced by application services so that non-HTTP callers cannot bypass them.

## Inspected paths

| Surface | Evidence observed | Assessment |
|---|---|---|
| Stock count HTTP | `WmsStockCountManagementController` obtains a `WmsAuthenticatedPrincipal` from Spring Authentication and delegates each operation to `WmsStockCountManagementService`. It does not implement authorization itself. | Correct layering; service remains the security boundary. |
| Stock count service | Each mutating operation resolves a permission through the service's access helper. Count and line queries are tenant-scoped; workflow transitions use locked headers and state predicates. Review/approval SoD is enforced in the service. | A per-line counter/reviewer gap was found and is being fixed in the dedicated Stock Count SoD work package. |
| Inbound HTTP | `WmsInboundReceivingController` extracts the WMS principal and delegates create, line, and post operations to `WmsInboundReceivingService`. | Verify service permission and tenant scope for every method; controller itself is not treated as sufficient enforcement. |
| Outbound HTTP | `WmsOutboundShipmentController` delegates list, get, create, add-line, allocation, and release to `WmsOutboundShipmentService`. | Verify service-level permission, tenant predicates, and final ship SoD; controller is not treated as sufficient enforcement. |
| Inter-warehouse transfer HTTP | `WmsInterWarehouseTransferController` delegates create, release, dispatch, and receive to `WmsInterWarehouseTransferService`. | Verify service-level permission, tenant predicates, dispatch/receive SoD, and idempotency at each transition. |
| Identity email worker | `WmsEmailOutboxDeliveryWorker` is property-gated, claims bounded batches through `WmsEmailOutboxStoragePort`, decrypts only for delivery, validates payload purpose and recipient against the envelope, and does not log tokens or provider exceptions. | This is a system delivery worker, not a tenant warehouse-command executor. Continue auditing storage tenant scoping, claim leases, retries, and secret handling. |

## Enforcement invariants

1. Controllers must not accept tenant IDs as authority. Tenant identity comes from the authenticated principal and is revalidated by the application service.
2. Every tenant-facing application-service entry point must require its exact permission before reading or mutating protected resources.
3. Reads and writes must include tenant predicates on the primary row and on joined child rows; public IDs are identifiers, not authorization.
4. State-changing operations must lock the aggregate and use conditional state transitions so stale or concurrent requests fail closed.
5. Inventory commitment and independent approval operations must enforce their SoD rules inside the service transaction, not only in controller annotations or UI.
6. Workers must use narrowly scoped storage ports, bounded claims, explicit tenant context where tenant data is touched, and auditable outcomes. A worker must not inherit an ambient HTTP principal.
7. Audit failures for critical operations must abort the transaction; security-relevant actions must not be silently accepted without durable evidence.
8. Internal service calls, scheduled jobs, event listeners, and retry paths are part of the attack surface and must receive the same scrutiny as HTTP routes.

## Remaining audit work before sign-off

- Enumerate all WMS controllers and map every endpoint to its exact service permission.
- Enumerate public application-service methods and verify no mutating entry point can be called without a permission-bearing principal or a narrowly scoped system capability.
- Review repository SQL for tenant predicates, especially joins, subqueries, bulk updates, and native queries.
- Review all scheduled workers, listeners, outbox processors, and asynchronous callbacks for tenant context, idempotency, retry safety, and authorization boundaries.
- Verify platform control-plane endpoints use only platform principals and the separate platform permission evaluator; no tenant principal or tenant role may authorize a platform operation.
- Add negative integration tests for direct service invocation, cross-tenant identifiers, missing permissions, revoked memberships, stale workflow states, and worker replay.
- Re-run the full MySQL Testcontainers suite, static analysis, dependency scanning, CodeQL, and DAST after the audit fixes.

## Sign-off criteria

This audit can be marked complete only when the endpoint-to-permission inventory and service/worker inventory are checked against the current source tree, all identified gaps are fixed, and negative tests prove denial without side effects. This document is an audit work product and does not itself certify completion.

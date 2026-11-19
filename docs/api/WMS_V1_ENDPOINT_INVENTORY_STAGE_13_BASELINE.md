# WMS v1 HTTP Endpoint Inventory — Stage 13 Baseline

**Baseline commit:** `0441d900f19c028bc5f413695205d04630e73226`  
**Status:** Source inventory; authentication, permission and runtime status must be verified per WP2/WP6.  
**Prefix:** `/api/wms/v1`

This inventory records controller route declarations, not a claim that every route is enabled in every deployment profile. All listed controllers currently carry `@Profile("wms")`, servlet-web conditional registration and `WmsIdentityHttpEnabledCondition`.

| Area | Method | Route |
|---|---|---|
| Authentication | POST | `/auth/credentials` |
| Authentication | POST | `/auth/tenant-selection` |
| Authentication | POST | `/auth/password-reset-requests` |
| Authentication | POST | `/auth/password-resets` |
| Authentication | POST | `/auth/email-verification-requests` |
| Authentication | POST | `/auth/email-verifications` |
| Authentication | POST | `/auth/password-changes` |
| Invitations | POST | `/invitations` |
| Invitations | POST | `/invitations/acceptances` |
| Invitations | POST | `/invitations/existing-account-acceptances` |
| Tenant profile | GET, PUT | `/tenants/current/profile` |
| Roles | GET, POST | `/tenants/current/roles` |
| Roles | PUT | `/tenants/current/roles/{rolePublicId}` |
| Roles | POST | `/tenants/current/roles/{rolePublicId}/deactivate` |
| Permissions | GET | `/tenants/current/permissions` |
| Role permissions | PUT | `/tenants/current/roles/{rolePublicId}/permissions` |
| Membership roles | GET | `/tenants/current/memberships/{membershipPublicId}/roles` |
| Membership roles | POST, DELETE | `/tenants/current/memberships/{membershipPublicId}/roles/{rolePublicId}` |
| Warehouses | GET, POST | `/tenants/current/warehouses` |
| Warehouses | GET, PUT | `/tenants/current/warehouses/{warehousePublicId}` |
| Warehouse status | POST | `/tenants/current/warehouses/{warehousePublicId}/status` |
| Locations | GET, POST | `/tenants/current/warehouses/{warehousePublicId}/locations` |
| Locations | GET, PUT | `/tenants/current/warehouses/{warehousePublicId}/locations/{locationPublicId}` |
| Location status | POST | `/tenants/current/warehouses/{warehousePublicId}/locations/{locationPublicId}/status` |
| Master data | GET | `/tenants/current/uoms` |
| Item categories | GET, POST | `/tenants/current/item-categories` |
| Item categories | PUT | `/tenants/current/item-categories/{categoryPublicId}` |
| Item category status | POST | `/tenants/current/item-categories/{categoryPublicId}/status` |
| Items | GET, POST | `/tenants/current/items` |
| Items | GET, PUT | `/tenants/current/items/{itemPublicId}` |
| Item status | POST | `/tenants/current/items/{itemPublicId}/status` |
| Item lookup | GET | `/tenants/current/items/lookup` |
| Item identifiers | GET, POST | `/tenants/current/items/{itemPublicId}/identifiers` |
| Item identifiers | PUT, DELETE | `/tenants/current/items/{itemPublicId}/identifiers/{identifierPublicId}` |
| UOM conversions | GET, POST | `/tenants/current/items/{itemPublicId}/uom-conversions` |
| UOM conversion deactivate | POST | `/tenants/current/items/{itemPublicId}/uom-conversions/deactivate` |
| Item lots | GET, POST | `/tenants/current/items/{itemPublicId}/lots` |
| Lot status | POST | `/tenants/current/items/{itemPublicId}/lots/{lotPublicId}/status` |
| Inventory posting | POST | `/tenants/current/inventory/postings` |
| Inventory balances | GET | `/tenants/current/inventory/balances` |
| Inventory ledger | GET | `/tenants/current/inventory/ledger` |
| Inbound receipts | GET, POST | `/tenants/current/inbound-receipts` |
| Inbound receipt | GET | `/tenants/current/inbound-receipts/{receiptPublicId}` |
| Inbound receipt lines | POST | `/tenants/current/inbound-receipts/{receiptPublicId}/lines` |
| Inbound receipt posting | POST | `/tenants/current/inbound-receipts/{receiptPublicId}/post` |
| Putaway tasks | GET, POST | `/tenants/current/putaway-tasks` |
| Putaway task | GET | `/tenants/current/putaway-tasks/{taskPublicId}` |
| Putaway task transitions | POST | `/tenants/current/putaway-tasks/{taskPublicId}/ready`, `/assign`, `/start` |
| Putaway confirmation | POST | `/tenants/current/putaway-tasks/{taskPublicId}/lines/{linePublicId}/confirm` |
| Outbound shipments | GET, POST | `/tenants/current/outbound-shipments` |
| Outbound shipment | GET | `/tenants/current/outbound-shipments/{shipmentPublicId}` |
| Shipment lines | POST | `/tenants/current/outbound-shipments/{shipmentPublicId}/lines` |
| Shipment allocation | POST | `/tenants/current/outbound-shipments/{shipmentPublicId}/lines/{linePublicId}/allocations` |
| Shipment release | POST | `/tenants/current/outbound-shipments/{shipmentPublicId}/release` |
| Picking tasks | GET, POST | `/tenants/current/picking-tasks` |
| Picking task | GET | `/tenants/current/picking-tasks/{taskPublicId}` |
| Picking transitions | POST | `/tenants/current/picking-tasks/{taskPublicId}/assign`, `/start` |
| Picking confirmation | POST | `/tenants/current/picking-tasks/{taskPublicId}/lines/{lineNumber}/confirm` |
| Packing sessions | GET, POST | `/tenants/current/packing-sessions` |
| Packing session | GET | `/tenants/current/packing-sessions/{sessionPublicId}` |
| Packing transitions | POST | `/tenants/current/packing-sessions/{sessionPublicId}/ready`, `/assign`, `/start`, `/complete` |
| Packages | POST | `/tenants/current/packing-sessions/{sessionPublicId}/packages` |
| Package lines | POST | `/tenants/current/packing-sessions/{sessionPublicId}/packages/{packagePublicId}/lines` |
| Package seal | POST | `/tenants/current/packing-sessions/{sessionPublicId}/packages/{packagePublicId}/seal` |
| Shipping | GET | `/tenants/current/shipping` |
| Shipping detail | GET | `/tenants/current/shipping/{shipmentPublicId}` |
| Ship shipment | POST | `/tenants/current/shipping/{shipmentPublicId}/ship` |
| Stock transfers | GET, POST | `/tenants/current/stock-transfers` |
| Stock transfer | GET | `/tenants/current/stock-transfers/{transferPublicId}` |
| Transfer transitions | POST | `/tenants/current/stock-transfers/{transferPublicId}/release`, `/dispatch`, `/receive` |
| Stock counts | GET, POST | `/tenants/current/stock-counts` |
| Stock count | GET | `/tenants/current/stock-counts/{countPublicId}` |
| Stock count transitions | POST | `/tenants/current/stock-counts/{countPublicId}/plan`, `/start`, `/submit`, `/approve`, `/cancel` |
| Stock count lines | POST | `/tenants/current/stock-counts/{countPublicId}/lines` |
| Stock count results | POST | `/tenants/current/stock-counts/{countPublicId}/lines/{linePublicId}/results` |
| Stock count review | POST | `/tenants/current/stock-counts/{countPublicId}/lines/{linePublicId}/review` |
| Inventory adjustments | GET | `/tenants/current/inventory-adjustments` |
| Inventory adjustment | GET | `/tenants/current/inventory-adjustments/{adjustmentPublicId}` |
| Count-linked adjustment | POST | `/tenants/current/stock-counts/{countPublicId}/inventory-adjustments` |
| Manual adjustment | POST | `/tenants/current/inventory-adjustments/manual` |
| Adjustment transitions | POST | `/tenants/current/inventory-adjustments/{adjustmentPublicId}/submit`, `/approve`, `/reject`, `/post` |
| Replenishment rules | GET, POST | `/tenants/current/replenishment-rules` |
| Replenishment rule | GET, PUT | `/tenants/current/replenishment-rules/{rulePublicId}` |
| Replenishment rule deactivate/plan | POST | `/tenants/current/replenishment-rules/{rulePublicId}/deactivate`, `/plan` |
| Replenishment tasks | GET, POST | `/tenants/current/replenishment-tasks` |
| Replenishment task | GET | `/tenants/current/replenishment-tasks/{taskPublicId}` |
| Replenishment task transitions | POST | `/tenants/current/replenishment-tasks/{taskPublicId}/release`, `/assign`, `/start`, `/confirm`, `/cancel` |
| Replenishment reversal | POST | `/tenants/current/replenishment-confirmations/{confirmationPublicId}/reverse` |
| Operational report | GET | `/tenants/current/reports/operational-summary` |

## Inventory caveats

- The route inventory is derived from controller annotations at the baseline SHA. It is not an authorization matrix.
- The exact public anonymous route allowlist is maintained separately in `WmsSecurityConfiguration` and the authentication API contract.
- Controller registration currently uses the same two-switch condition across identity and operational controllers. WP2 must test and resolve the behavior before this list is treated as a deployment guarantee.
- Permission codes, per-route status codes, request/response schemas and query bounds are intentionally deferred to the detailed WP2–WP9 audit.
- No endpoint should be considered frontend-ready solely because a controller mapping exists.

# REST API Reference

Base path: `/api/v1.0`

This reference is extracted from the current controller mappings and request DTOs in the backend repository. It documents the HTTP boundary, not a guarantee that every workflow has complete integration-test coverage.

## Authentication

| Method | Path | Purpose | Success response |
|---|---|---|---|
| POST | `/auth/register` | Register account | `201 Created`, `AuthResponse` |
| POST | `/auth/login` | Authenticate account | `200 OK`, `AuthResponse` |

Request fields (`AuthRequest`):
- `email` — required, valid email format.
- `password` — required.
- `name` — used during registration.
- `role` — used during registration.

## Warehouse and storage

| Method | Path | Purpose | Success response |
|---|---|---|---|
| POST | `/warehouses` | Create warehouse | `201 Created`, `WarehouseResponse` |
| GET | `/warehouses` | List warehouses | `200 OK`, paged `WarehouseResponse` |
| POST | `/bins` | Create storage bin | `201 Created`, `BinResponse` |
| GET | `/bins/warehouse/{warehouseId}` | List bins for a warehouse | `200 OK`, list of `BinResponse` |

`WarehouseRequest` fields:
- `code` — required, 3–50 characters.
- `name` — required.
- `address` — optional.

`BinRequest` fields:
- `warehousePublicId` — required.
- `binCode` — required.
- `zoneName`, `rackNumber` — optional.
- `maxWeightCapacity` — required and positive.
- `minWeightThreshold` — optional.

## Item catalog

| Method | Path | Purpose | Success response |
|---|---|---|---|
| POST | `/items` | Create item | `201 Created`, `ItemResponse` |
| GET | `/items` | List items | `200 OK`, paged `ItemResponse` |

`ItemRequest` fields:
- `sku`, `name`, `baseUom` — required.
- `minStockLevel` — required.
- `weightPerUnit` — required and positive.
- `description` — optional.

## Inventory movements

| Method | Path | Purpose | Success response |
|---|---|---|---|
| POST | `/inbound` | Record inbound movement | `200 OK`, text message |
| POST | `/outbound` | Record outbound movement | `200 OK`, text message |
| POST | `/transfer` | Transfer stock between bins | `200 OK`, text message |
| GET | `/inventory/stocks` | Query current stock | `200 OK`, paged `StockResponse` |
| GET | `/inventory/ledger` | Query movement history | `200 OK`, paged `LedgerResponse` |

`InboundRequest` fields:
- `requestId`, `sku`, `binCode` — required.
- `quantity` — required and positive.
- `referenceNo`, `note` — optional.

`OutboundRequest` fields:
- `requestId`, `sku`, `binCode` — required.
- `quantity` — required and positive.
- `referenceNo`, `note` — optional.

`TransferRequest` fields:
- `requestId`, `sku`, `sourceBinCode`, `destinationBinCode` — required.
- `quantity` — required and positive.
- `note` — optional.

## Dashboard

| Method | Path | Purpose | Success response |
|---|---|---|---|
| GET | `/dashboard/summary` | Retrieve dashboard summary | `200 OK`, `DashboardSummaryResponse` |

## Pagination

Warehouse, item, stock, and ledger list methods accept Spring Data `Pageable`. Standard query parameters such as `page`, `size`, and `sort` are supported by the controller method signature.

## Authentication notes

Protected endpoint access is configured through Spring Security and the JWT request filter. The exact public/protected route policy and role-to-endpoint matrix should be reviewed in the security configuration before relying on this page as an authorization matrix.

## Example request body

Example inbound request shape (use values that exist in the local database):

```json
{
  "requestId": "replace-with-a-unique-request-id",
  "sku": "SKU-EXAMPLE",
  "binCode": "BIN-EXAMPLE",
  "quantity": 5,
  "referenceNo": "PO-EXAMPLE",
  "note": "Initial stock receipt"
}
```

The request DTO applies validation for required fields and a positive quantity. This example illustrates the DTO shape; it is not a recorded successful API test.

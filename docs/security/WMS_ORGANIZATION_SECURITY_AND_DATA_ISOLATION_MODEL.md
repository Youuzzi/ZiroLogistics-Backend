# WMS Organization, Security & Data Isolation Model

- **Document ID:** DB-10
- **Version:** 0.1
- **Status:** Proposed — initial ownership and access model
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD; DB-06 Warehouse Location Model
- **Product stage:** Initial release for small and medium warehouses\n> **V1 decision authority:** For the first WMS schema, DB-14 — WMS Final Database Design Baseline is authoritative. Any OPEN/PROPOSED alternatives in this reference document are superseded where DB-14 records a FROZEN V1 decision. Retain this document for rationale and detailed requirements; do not reopen a closed DB-14 decision without new evidence or an approved scope change.\n

## 1. Purpose

This document defines the first-release company ownership and warehouse access model for the WMS database.

The initial product serves one organization/company that may operate multiple warehouses. It is not a full multi-tenant SaaS platform in the first release.

## 2. Ownership model

Proposed ownership path:

Company → Warehouse → Location → Inventory and warehouse operations

- A Company owns its warehouses and item master data.
- A Warehouse belongs to exactly one Company.
- A Location belongs to exactly one Warehouse.
- Inventory balances and operational documents must resolve to one Company through their warehouse/location ownership.
- Users and service identities must be authenticated and authorized before accessing operational records.

The company identity is explicit even though the initial deployment may contain only one company. This avoids treating the absence of an owner as an implicit global scope.

The model must not introduce tenant provisioning, subscription billing, cross-tenant administration, or complex tenant lifecycle management in the first release.

## 3. Role, permission, and warehouse assignment

These concepts are separate:

- **Role:** named group of responsibilities, such as warehouse operator or supervisor.
- **Permission:** backend-enforced action such as receiving confirmation, stock adjustment approval, or dispatch posting.
- **User-Role Assignment:** which roles a user holds.
- **User-Warehouse Assignment:** which warehouses the user may operate.

A user may have permission to perform a type of operation but still lack access to a particular warehouse. Both checks are required.

The backend is the security boundary. Frontend route visibility or disabled buttons are not authorization controls.

Exact initial role names and permission catalogue must be approved against the product's actual operating model; do not infer them from generic WMS examples.

## 4. Data access rules

Every operation must validate:
1. authenticated actor identity;
2. permission for the requested action;
3. company ownership of the target records;
4. warehouse assignment where the operation is warehouse-scoped;
5. consistency of item, location, document, and inventory ownership;
6. active status and lifecycle eligibility of master records.

A request must not be able to combine an item from one company with a location or document from another company.

Queries must apply ownership and warehouse scope in the backend. Hiding records in the frontend is insufficient.

## 5. Database enforcement strategy

Prefer database-enforceable ownership integrity where practical:
- Company primary/unique key referenced by Warehouse and Company-owned masters.
- Warehouse unique ownership/code constraints.
- Location references Warehouse.
- Operational records reference their owning Warehouse and relevant master records.
- Composite foreign keys may be used to enforce that a referenced location belongs to the same warehouse/company as its document.
- User-Warehouse Assignment has a unique constraint on the user/warehouse pair and active lifecycle semantics.

The final schema must avoid redundant owner columns that can disagree with the ownership path. If denormalized company_id is stored on operational rows for query performance or isolation, a database-enforced composite relationship or equivalent integrity mechanism must prevent mismatch.

## 6. User identity and lifecycle

- User identifiers must be stable and must not depend on mutable email/display name.
- Email uniqueness and normalization rules must be explicit.
- Deactivated users cannot initiate new operations.
- Historical actor references remain valid for audit even after a user is deactivated.
- Password hashes and authentication secrets must never be stored in plaintext.
- Password, token, and signing-secret lifecycle belongs to application security configuration, not public seed data.
- Service identities, if introduced, must be distinguishable from human users and have least privilege.

## 7. Future multi-company and multi-tenant expansion

Future expansion may require:
- multiple companies in one installation;
- organization membership;
- tenant identity and isolation;
- tenant-aware uniqueness;
- tenant provisioning and lifecycle;
- cross-tenant administration controls;
- stronger isolation tests and possibly database/schema separation.

These changes require a separate architecture decision and security review. The initial company ownership model should avoid hard-coded assumptions that make expansion impossible, but it must not implement full SaaS complexity prematurely.

## 8. Required security tests

- user without the required permission cannot perform the operation;
- user with permission but without warehouse assignment cannot access that warehouse;
- user assigned to one warehouse cannot access another warehouse's inventory;
- cross-company item/location/document combinations are rejected;
- inactive user cannot create or post new operations;
- deactivated master data cannot be used for new movement where policy prohibits it;
- direct API calls cannot bypass frontend restrictions;
- historical records remain readable to authorized auditors after actor deactivation;
- warehouse access revocation takes effect for subsequent requests;
- list, detail, export, and mutation endpoints all enforce the same scope.

## 9. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB10-01 | Company model | One explicit Company in first release, capable of owning multiple warehouses |
| DB10-02 | User-company relation | Confirm whether all users are company-scoped or global internal operators are needed |
| DB10-03 | Warehouse access | Explicit user-to-warehouse assignment |
| DB10-04 | Role model | Role and permission remain separate; approve actual role catalogue |
| DB10-05 | Item ownership | Company-owned item master unless shared catalogue is explicitly required |
| DB10-06 | Data enforcement | Backend authorization plus relational ownership constraints |
| DB10-07 | Future tenant model | Defer full multi-tenancy to a separate architecture decision |

## 10. Readiness gate

The security schema is ready when:
- company/user relationship is approved;
- role and permission responsibilities are defined;
- warehouse assignment rules are approved;
- uniqueness and ownership scopes are specified;
- cross-company/cross-warehouse integrity can be enforced and tested;
- historical actor retention and deactivation rules are defined.

---

**Public documentation note:** This document describes a proposed first-release security model. It does not claim that multi-tenant SaaS isolation or every authorization workflow is implemented.
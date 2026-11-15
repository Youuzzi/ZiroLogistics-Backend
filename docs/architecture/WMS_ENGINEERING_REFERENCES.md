# WMS Engineering References

**Reviewed:** 2026-11-16  
**Purpose:** Traceable primary-source references used by `WMS_ENGINEERING_PRINCIPLES_AND_QUALITY_GATES.md`.

This list deliberately separates formal/open standards from product-vendor documentation and implementation technology documentation. Vendor documentation describes how that vendor's product works; it is not automatically a universal WMS requirement.

## A. Traceability and supply-chain visibility standards

### GS1 Global Traceability Standard 2.0
- Official standard: https://ref.gs1.org/standards/global-traceability/2.0.0/
- GS1 overview: https://support.gs1.org/support/solutions/articles/43000734535-what-is-the-gs1-traceability-standard-
- Use in ZiroLogistics: traceability system design, Critical Tracking Events (CTEs), Key Data Elements (KDEs), and minimum event context. Requirement R23 specifies event date/time (including time zone and UTC offset), traceable-object ID, location ID, business process step/disposition, and responsible party for applicable events.
- Applicability note: adopt the data requirements relevant to the products, customers, jurisdictions, and trading-partner agreements in scope. Do not imply regulatory compliance solely because fields exist.

### GS1 EPCIS 2.0
- Official standard: https://ref.gs1.org/standards/epcis/2.0.0/
- GS1 overview: https://support.gs1.org/support/solutions/articles/43000755325-what-is-electronic-product-code-information-services-epcis-
- Use in ZiroLogistics: reference model for interoperable visibility events across applications/organizations. EPCIS describes standardized capture/query interfaces and event semantics; it does not prescribe the internal WMS database or service implementation.
- Applicability note: EPCIS compatibility is a separate integration/conformance scope. Internal ledger records are not automatically EPCIS events.

### GS1 System Architecture
- Official architecture: https://ref.gs1.org/architecture/system-architecture/
- Use in ZiroLogistics: understand the Identify, Capture, Share model and the relationship between identifiers, barcode/RFID data capture, master data, transactions, and visibility events.

## B. WMS operational process references (vendor-specific examples)

### Microsoft Dynamics 365 Supply Chain Management — Warehouse management overview
- Official documentation: https://learn.microsoft.com/dynamics365/supply-chain/warehousing/warehouse-management-overview
- Use in ZiroLogistics: compare warehouse work concepts, work templates, work pools, location directives, location capacity/volumetrics, inventory status, batch/serial support, and inbound/outbound process configuration.
- Applicability note: configuration concepts and workflow details are Dynamics 365 product behavior. Use as design input, not as mandatory ZiroLogistics behavior.

### Microsoft Dynamics 365 — Cycle counting
- Official documentation: https://learn.microsoft.com/dynamics365/supply-chain/warehousing/cycle-counting
- Use in ZiroLogistics: compare cycle-count work, pending review, discrepancy resolution, and warehouse/location prerequisites.
- Applicability note: our own count lifecycle and adjustment controls must be defined against the frozen schema and approved business policy.

### SAP Extended Warehouse Management — Inbound Delivery Process
- Official documentation: https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/3d97bec9bf1649099384bb8167df3cf2/88ad54fa2c9044b7b5406a511783589e.html
- Use in ZiroLogistics: compare inbound delivery, goods receipt, warehouse task creation, putaway destination determination, and task confirmation.
- Applicability note: this page is for a specific SAP EWM version/process. It is not a universal mandatory lifecycle.

## C. Application security

### OWASP Application Security Verification Standard (ASVS) 5.0.0
- Official project and stable release: https://asvs.owasp.org/
- OWASP project page: https://owasp.org/projects/asvs
- Use in ZiroLogistics: a structured basis for security requirements and verification tests for web applications and services. Select an appropriate target level and map applicable requirements to evidence.
- Applicability note: do not claim ASVS compliance until the selected version/level and applicable requirements have been assessed and verified.

## D. Transaction and database correctness

### Spring Framework — Transaction Management
- Official reference: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Rollback semantics: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html
- Use in ZiroLogistics: define transaction boundaries and understand declarative transaction behavior, propagation, and rollback rules. In particular, default rollback behavior and checked-exception rules must be considered explicitly.

### MySQL 8.4 — InnoDB Locking and Transaction Model
- Official reference: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-transaction-model.html
- Locking reads: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html
- Isolation levels: https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html
- Use in ZiroLogistics: implement and test the actual MySQL locking, snapshot, and isolation semantics used by WMS. Do not infer concurrency correctness from H2-only tests.

### MySQL 8.4 — Deadlocks
- Official reference: https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks.html
- Handling guidance: https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html
- Use in ZiroLogistics: keep transactions short, acquire locks in a consistent order, index locking predicates, and handle transaction rollback/retry after deadlocks.

### Redgate Flyway — Validate
- Official reference: https://documentation.red-gate.com/flyway/reference/commands/validate
- Use in ZiroLogistics: validate applied migration names/types/checksums against available migrations. Migration tests must run against disposable databases.
- Applicability note: validation detects migration history/checksum drift; it does not prove that business data or application mappings are correct.

## E. Product-specific decisions still required

The references above do not settle these decisions for ZiroLogistics. They require product analysis and, where appropriate, an ADR:

- Tenant model and whether users may hold multiple memberships/active tenant contexts.
- Permission granularity and warehouse/location resource scopes.
- Which workflows require maker-checker approval and which may be single-actor.
- Whether negative inventory is ever allowed and under what controlled conditions.
- Reservation expiry, allocation priority, and release semantics.
- Partial receipt, over-receipt, under-receipt, damage, and discrepancy policies.
- Transfer in-transit ownership and when source/destination balances change.
- Lot/serial/expiry requirements by item category and target industry.
- FIFO/FEFO policy and override authorization.
- Business timezone, warehouse timezone, cutoff, and timestamp representation.
- Audit retention, privacy, and access policy by deployment jurisdiction.
- Backup/restore objectives, RPO/RTO, and disaster recovery ownership.
- EPCIS/EDI/API integration scope and trading-partner conformance.

**Review rule:** check source version and applicability again when a feature is implemented. Record the source section/requirement and the corresponding ZiroLogistics test or ADR in the feature PR.

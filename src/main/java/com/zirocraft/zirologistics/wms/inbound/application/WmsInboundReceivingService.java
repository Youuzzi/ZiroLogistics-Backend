package com.zirocraft.zirologistics.wms.inbound.application;

import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped inbound receiving. All stock changes are delegated to the inventory posting boundary.
 * Receipt quantities, traceability links, stock ledger changes and audit are committed atomically.
 */
@Service
@Profile("wms")
public class WmsInboundReceivingService {
    private static final Set<String> RECEIPT_TYPES = Set.of("PURCHASE", "OTHER");
    private static final int MAX_POSTING_LEGS = 200;

    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsInboundReceivingService(
            WmsMembershipAccessService accessService,
            WmsInventoryPostingService postingService,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_INBOUND_RECEIPT_READ");
        return jdbc.queryForList("""
                SELECT r.public_id, r.document_number, r.external_reference, r.receipt_type,
                       r.status, r.expected_at, r.received_at, r.posted_at, r.notes,
                       w.public_id AS warehouse_public_id, w.warehouse_code, w.warehouse_name,
                       bp.public_id AS supplier_public_id, bp.partner_code AS supplier_code,
                       bp.partner_name AS supplier_name, r.version, r.created_at, r.updated_at
                FROM inbound_receipts r
                JOIN warehouses w ON w.id = r.warehouse_id AND w.tenant_id = r.tenant_id
                JOIN business_partners bp
                  ON bp.id = r.supplier_partner_id AND bp.tenant_id = r.tenant_id
                WHERE r.tenant_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String receiptPublicId) {
        Access access = access(principal, "WMS_INBOUND_RECEIPT_READ");
        Map<String, Object> receipt = receipt(access.tenantId(), receiptPublicId);
        Map<String, Object> result = new LinkedHashMap<>(publicReceipt(receipt));
        result.put("lines", jdbc.queryForList("""
                SELECT l.public_id, l.line_number, i.public_id AS item_public_id, i.sku, i.item_name,
                       u.uom_code, l.expected_qty, l.received_qty, l.accepted_qty, l.rejected_qty,
                       l.status, l.rejection_reason, l.notes
                FROM inbound_receipt_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN units_of_measure u ON u.id = l.uom_id
                WHERE l.tenant_id = ? AND l.inbound_receipt_id = ?
                ORDER BY l.line_number
                """, access.tenantId(), number(receipt, "id")));
        return result;
    }

    @Transactional
    public Map<String, Object> create(
            WmsAuthenticatedPrincipal principal, ReceiptCommand command) {
        Objects.requireNonNull(command, "Receipt request is required");
        lockTenantForPrincipal(principal);
        Access access = access(principal, "WMS_INBOUND_RECEIPT_CREATE");

        String documentNumber = required(command.documentNumber(), "document number", 60).toUpperCase(Locale.ROOT);
        String receiptType = enumValue(command.receiptType(), RECEIPT_TYPES, "receipt type");
        Map<String, Object> warehouse = requiredRow("""
                SELECT id, status FROM warehouses WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(command.warehousePublicId(), "warehouse public ID"), "Warehouse not found");
        if (!"ACTIVE".equals(warehouse.get("status"))) throw new IllegalArgumentException("Warehouse is not active");

        Map<String, Object> supplier = requiredRow("""
                SELECT bp.id, bp.status
                FROM business_partners bp
                JOIN business_partner_roles bpr
                  ON bpr.business_partner_id = bp.id AND bpr.tenant_id = bp.tenant_id
                WHERE bp.tenant_id = ? AND bp.public_id = ? AND bpr.role_type = 'SUPPLIER'
                """, access.tenantId(), required(command.supplierPublicId(), "supplier public ID"), "Supplier not found");
        if (!"ACTIVE".equals(supplier.get("status"))) throw new IllegalArgumentException("Supplier is not active");

        Long supplierAddressId = null;
        if (command.supplierAddressPublicId() != null && !command.supplierAddressPublicId().isBlank()) {
            supplierAddressId = requiredLong("""
                    SELECT id FROM business_partner_addresses
                    WHERE tenant_id = ? AND business_partner_id = ? AND public_id = ?
                    """, access.tenantId(), number(supplier, "id"), command.supplierAddressPublicId().trim(),
                    "Supplier address not found");
        }

        Map<String, Object> location = requiredRow("""
                SELECT id, status, is_receivable
                FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                """, access.tenantId(), number(warehouse, "id"),
                required(command.receivingLocationPublicId(), "receiving location public ID"),
                "Receiving location not found");
        if (!"ACTIVE".equals(location.get("status")) || !Boolean.TRUE.equals(bool(location.get("is_receivable")))) {
            throw new IllegalArgumentException("Receiving location must be active and marked receivable");
        }

        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO inbound_receipts
                      (public_id, tenant_id, warehouse_id, supplier_partner_id, supplier_address_id,
                       document_number, external_reference, receipt_type, status, expected_at,
                       receiving_location_id, created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                    """,
                    publicId, access.tenantId(), number(warehouse, "id"), number(supplier, "id"),
                    supplierAddressId, documentNumber, optional(command.externalReference(), 100),
                    receiptType, command.expectedAt(), number(location, "id"), access.membershipId(),
                    optional(command.notes(), 1000));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Inbound receipt document number already exists", duplicate);
        }

        Map<String, Object> created = receipt(access.tenantId(), publicId);
        audit(access, "INBOUND_RECEIPT_CREATED", publicId, Map.of(), publicReceipt(created));
        return publicReceipt(created);
    }

    @Transactional
    public Map<String, Object> addLine(
            WmsAuthenticatedPrincipal principal, String receiptPublicId, ReceiptLineCommand command) {
        Objects.requireNonNull(command, "Receipt line request is required");
        lockTenantForPrincipal(principal);
        Access access = access(principal, "WMS_INBOUND_RECEIPT_UPDATE");
        Map<String, Object> receipt = receipt(access.tenantId(), receiptPublicId);
        if (!"DRAFT".equals(receipt.get("status"))) {
            throw new IllegalStateException("Only draft receipts can be changed");
        }

        Map<String, Object> item = requiredRow("""
                SELECT id, base_uom_id, tracking_method, is_stock_item, is_purchasable, status
                FROM items WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(command.itemPublicId(), "item public ID"), "Item not found");
        if (!"ACTIVE".equals(item.get("status")) || !Boolean.TRUE.equals(bool(item.get("is_stock_item")))
                || !Boolean.TRUE.equals(bool(item.get("is_purchasable"))) ) {
            throw new IllegalArgumentException("Inbound receipt requires an active purchasable stock item");
        }
        BigDecimal expected = quantity(command.expectedQty(), true);
        int lineNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(line_number), 0) + 1
                FROM inbound_receipt_lines WHERE tenant_id = ? AND inbound_receipt_id = ?
                """, Integer.class, access.tenantId(), number(receipt, "id"));
        String linePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO inbound_receipt_lines
                  (public_id, tenant_id, inbound_receipt_id, line_number, item_id, uom_id,
                   expected_qty, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, linePublicId, access.tenantId(), number(receipt, "id"), lineNumber,
                number(item, "id"), number(item, "base_uom_id"), expected,
                optional(command.notes(), 1000));

        Map<String, Object> created = requiredRow("""
                SELECT l.public_id, l.line_number, i.public_id AS item_public_id, i.sku, i.item_name,
                       u.uom_code, l.expected_qty, l.status, l.notes
                FROM inbound_receipt_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN units_of_measure u ON u.id = l.uom_id
                WHERE l.tenant_id = ? AND l.public_id = ?
                """, access.tenantId(), linePublicId, "Created receipt line not found");
        audit(access, "INBOUND_RECEIPT_LINE_ADDED", linePublicId,
                Map.of("receiptPublicId", receiptPublicId), created);
        return created;
    }

    /**
     * Receives and posts the complete receipt in one transaction. The header briefly transitions
     * through COMPLETED before POSTED; no intermediate state is externally observable.
     */
    @Transactional
    public Map<String, Object> post(
            WmsAuthenticatedPrincipal principal, String receiptPublicId, PostReceiptCommand command) {
        Objects.requireNonNull(command, "Receipt posting request is required");
        lockTenantForPrincipal(principal);
        Access access = access(principal, "WMS_INBOUND_RECEIPT_POST");
        if (command.requestId() == null) throw new IllegalArgumentException("Request ID is required");
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("At least one receipt line is required");
        }
        Map<String, Object> receipt = receipt(access.tenantId(), receiptPublicId);
        if (!"DRAFT".equals(receipt.get("status")) && !"IN_PROGRESS".equals(receipt.get("status"))) {
            throw new IllegalStateException("Receipt is not eligible for posting");
        }

        List<Map<String, Object>> storedLines = jdbc.queryForList("""
                SELECT l.id, l.public_id, l.item_id, l.expected_qty, l.status,
                       i.public_id AS item_public_id, i.tracking_method
                FROM inbound_receipt_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.inbound_receipt_id = ?
                ORDER BY l.line_number
                """, access.tenantId(), number(receipt, "id"));
        if (storedLines.isEmpty()) throw new IllegalStateException("Receipt must contain at least one line");

        Map<String, LineReceiptCommand> supplied = new HashMap<>();
        for (LineReceiptCommand line : command.lines()) {
            if (line == null || line.linePublicId() == null || line.linePublicId().isBlank()
                    || supplied.put(line.linePublicId(), line) != null) {
                throw new IllegalArgumentException("Receipt line references must be present and unique");
            }
        }
        if (supplied.size() != storedLines.size()) {
            throw new IllegalArgumentException("Every receipt line must be included exactly once");
        }

        List<WmsInventoryPostingService.PostingLeg> legs = new ArrayList<>();
        List<SerialLink> serialLinks = new ArrayList<>();
        List<LotLink> lotLinks = new ArrayList<>();
        List<LineUpdate> updates = new ArrayList<>();

        for (Map<String, Object> stored : storedLines) {
            LineReceiptCommand line = supplied.remove((String) stored.get("public_id"));
            if (line == null || !"PENDING".equals(stored.get("status"))) {
                throw new IllegalStateException("Receipt line is missing or already processed");
            }
            BigDecimal received = quantity(line.receivedQty(), false);
            BigDecimal accepted = quantity(line.acceptedQty(), false);
            BigDecimal rejected = quantity(line.rejectedQty(), false);
            if (received.compareTo(accepted.add(rejected)) != 0) {
                throw new IllegalArgumentException("Received quantity must equal accepted plus rejected quantity");
            }
            BigDecimal expected = (BigDecimal) stored.get("expected_qty");
            if (expected.signum() > 0 && received.compareTo(expected) > 0) {
                throw new IllegalArgumentException("Received quantity cannot exceed expected quantity");
            }
            if (rejected.signum() > 0 && (line.rejectionReason() == null || line.rejectionReason().isBlank())) {
                throw new IllegalArgumentException("A rejection reason is required for rejected quantity");
            }

            String tracking = (String) stored.get("tracking_method");
            List<LotAllocation> lots = line.lots() == null ? List.of() : line.lots();
            List<SerialReceipt> serials = line.serials() == null ? List.of() : line.serials();
            long itemId = number(stored, "item_id");
            String itemPublicId = (String) stored.get("item_public_id");
            String warehousePublicId = (String) receipt.get("warehouse_public_id");
            String locationPublicId = (String) receipt.get("receiving_location_public_id");

            if ("NONE".equals(tracking)) {
                if (!lots.isEmpty() || !serials.isEmpty()) throw new IllegalArgumentException("Untracked items cannot include lot or serial data");
                if (accepted.signum() > 0) legs.add(leg(warehousePublicId, locationPublicId, itemPublicId, null, null, accepted));
            } else if ("LOT".equals(tracking)) {
                if (!serials.isEmpty()) throw new IllegalArgumentException("Lot-tracked items cannot include serial data");
                BigDecimal allocated = BigDecimal.ZERO.setScale(4);
                Set<String> seenLots = new HashSet<>();
                for (LotAllocation allocation : lots) {
                    if (allocation == null || allocation.lotPublicId() == null || !seenLots.add(allocation.lotPublicId())) {
                        throw new IllegalArgumentException("Lot allocations must be unique and complete");
                    }
                    BigDecimal qty = quantity(allocation.quantity(), true);
                    Map<String, Object> lot = activeLot(access.tenantId(), itemId, allocation.lotPublicId());
                    allocated = allocated.add(qty);
                    lotLinks.add(new LotLink(number(stored, "id"), itemId, number(lot, "id"), qty));
                    legs.add(leg(warehousePublicId, locationPublicId, itemPublicId, allocation.lotPublicId(), null, qty));
                }
                if (allocated.compareTo(accepted) != 0) throw new IllegalArgumentException("Lot quantities must equal accepted quantity");
            } else if ("SERIAL".equals(tracking) || "LOT_AND_SERIAL".equals(tracking)) {
                if (!lots.isEmpty()) throw new IllegalArgumentException("Serial-tracked receipt lines derive lot quantities from serial assignments");
                if (accepted.stripTrailingZeros().scale() > 0 || accepted.intValueExact() != serials.size()) {
                    throw new IllegalArgumentException("Accepted quantity must equal the number of serials");
                }
                Set<String> seenSerials = new HashSet<>();
                Map<String, BigDecimal> serialLotCounts = new HashMap<>();
                for (SerialReceipt serial : serials) {
                    if (serial == null || serial.serialNumber() == null || serial.serialNumber().isBlank()
                            || !seenSerials.add(serial.serialNumber().trim())) {
                        throw new IllegalArgumentException("Serial numbers must be present and unique");
                    }
                    String lotPublicId = serial.lotPublicId();
                    if ("LOT_AND_SERIAL".equals(tracking)) {
                        if (lotPublicId == null || lotPublicId.isBlank()) throw new IllegalArgumentException("Each serial requires a lot");
                        activeLot(access.tenantId(), itemId, lotPublicId);
                        serialLotCounts.merge(lotPublicId, BigDecimal.ONE.setScale(4), BigDecimal::add);
                    } else if (lotPublicId != null && !lotPublicId.isBlank()) {
                        throw new IllegalArgumentException("Serial-only items cannot include a lot");
                    }
                    legs.add(leg(warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                            serial.serialNumber().trim(), BigDecimal.ONE.setScale(4)));
                    serialLinks.add(new SerialLink(number(stored, "id"), itemId, serial.serialNumber().trim()));
                }
                for (Map.Entry<String, BigDecimal> entry : serialLotCounts.entrySet()) {
                    Map<String, Object> lot = activeLot(access.tenantId(), itemId, entry.getKey());
                    lotLinks.add(new LotLink(number(stored, "id"), itemId, number(lot, "id"), entry.getValue()));
                }
            } else {
                throw new IllegalArgumentException("Item tracking method is unsupported");
            }

            updates.add(new LineUpdate(number(stored, "id"), received, accepted, rejected,
                    accepted.signum() > 0 ? "COMPLETED" : "REJECTED",
                    optional(line.rejectionReason(), 500)));
        }
        if (!supplied.isEmpty()) throw new IllegalArgumentException("Unknown receipt line was supplied");
        if (legs.size() > MAX_POSTING_LEGS) throw new IllegalArgumentException("Receipt exceeds the maximum of 200 inventory posting legs");

        // Persist receipt facts before posting; a posting failure rolls the entire transaction back.
        for (LineUpdate update : updates) {
            jdbc.update("""
                    UPDATE inbound_receipt_lines
                    SET received_qty = ?, accepted_qty = ?, rejected_qty = ?, status = ?,
                        rejection_reason = ?, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND tenant_id = ? AND status = 'PENDING'
                    """, update.received(), update.accepted(), update.rejected(), update.status(),
                    update.rejectionReason(), update.lineId(), access.tenantId());
        }
        for (LotLink link : lotLinks) {
            jdbc.update("""
                    INSERT INTO inbound_receipt_line_lots
                      (tenant_id, inbound_receipt_line_id, item_id, lot_id, accepted_qty)
                    VALUES (?, ?, ?, ?, ?)
                    """, access.tenantId(), link.lineId(), link.itemId(), link.lotId(), link.quantity());
        }

        if (!legs.isEmpty()) {
            postingService.post(principal, new WmsInventoryPostingService.PostingCommand(
                    command.requestId(), "RECEIPT", legs, "Inbound receipt " + receipt.get("document_number")));
        }

        for (SerialLink link : serialLinks) {
            Long serialId = requiredLong("""
                    SELECT id FROM inventory_serials
                    WHERE tenant_id = ? AND item_id = ? AND serial_number = ?
                    """, access.tenantId(), link.itemId(), link.serialNumber(), "Posted serial was not registered");
            jdbc.update("""
                    INSERT INTO inbound_receipt_line_serials
                      (tenant_id, inbound_receipt_line_id, item_id, serial_id)
                    VALUES (?, ?, ?, ?)
                    """, access.tenantId(), link.lineId(), link.itemId(), serialId);
        }

        jdbc.update("""
                UPDATE inbound_receipts
                SET status = 'COMPLETED', received_at = UTC_TIMESTAMP(6),
                    received_by_membership_id = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status IN ('DRAFT', 'IN_PROGRESS')
                """, access.membershipId(), number(receipt, "id"), access.tenantId());
        jdbc.update("""
                UPDATE inbound_receipts
                SET status = 'POSTED', posted_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'COMPLETED'
                """, number(receipt, "id"), access.tenantId());

        Map<String, Object> posted = receipt(access.tenantId(), receiptPublicId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", command.requestId().toString());
        payload.put("lineCount", updates.size());
        payload.put("postingLegCount", legs.size());
        payload.put("acceptedQty", updates.stream().map(LineUpdate::accepted).reduce(BigDecimal.ZERO, BigDecimal::add));
        payload.put("rejectedQty", updates.stream().map(LineUpdate::rejected).reduce(BigDecimal.ZERO, BigDecimal::add));
        audit(access, "INBOUND_RECEIPT_POSTED", receiptPublicId, Map.of("status", "DRAFT"), payload);
        return publicReceipt(posted);
    }

    private Map<String, Object> receipt(long tenantId, String publicId) {
        return requiredRow("""
                SELECT r.id, r.public_id, r.document_number, r.external_reference, r.receipt_type,
                       r.status, r.expected_at, r.received_at, r.posted_at, r.notes, r.version,
                       r.created_at, r.updated_at, w.public_id AS warehouse_public_id,
                       w.warehouse_code, w.warehouse_name, loc.public_id AS receiving_location_public_id,
                       bp.public_id AS supplier_public_id, bp.partner_code AS supplier_code,
                       bp.partner_name AS supplier_name
                FROM inbound_receipts r
                JOIN warehouses w ON w.id = r.warehouse_id AND w.tenant_id = r.tenant_id
                JOIN warehouse_locations loc
                  ON loc.id = r.receiving_location_id AND loc.warehouse_id = r.warehouse_id
                     AND loc.tenant_id = r.tenant_id
                JOIN business_partners bp
                  ON bp.id = r.supplier_partner_id AND bp.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND r.public_id = ?
                """, tenantId, required(publicId, "receipt public ID"), "Inbound receipt not found");
    }

    private Map<String, Object> activeLot(long tenantId, long itemId, String lotPublicId) {
        Map<String, Object> lot = requiredRow("""
                SELECT id, status FROM inventory_lots
                WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                """, tenantId, itemId, required(lotPublicId, "lot public ID"), "Lot not found for item");
        if (!"ACTIVE".equals(lot.get("status"))) throw new IllegalArgumentException("Lot is not active");
        return lot;
    }

    private WmsInventoryPostingService.PostingLeg leg(
            String warehousePublicId, String locationPublicId, String itemPublicId,
            String lotPublicId, String serialNumber, BigDecimal quantity) {
        return new WmsInventoryPostingService.PostingLeg(
                warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                null, serialNumber, "AVAILABLE", quantity);
    }

    private Access access(WmsAuthenticatedPrincipal principal, String permission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required inbound receiving permission is missing");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void lockTenantForPrincipal(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        lockTenant(principal.tenantId());
    }

    private void lockTenant(long tenantId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", Long.class, tenantId);
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private void audit(Access access, String eventType, String subjectPublicId,
                       Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Inbound receipt audit payload could not be serialized", failure);
        }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, ?, ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType,
                subjectPublicId, json, access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Map<String, Object> publicReceipt(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("id");
        return result;
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException(message);
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, Object c, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b, c);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private Long requiredLong(String sql, Object a, Object b, Object c, String message) {
        List<Long> values = jdbc.queryForList(sql, Long.class, a, b, c);
        if (values.isEmpty()) throw new IllegalArgumentException(message);
        return values.getFirst();
    }

    private Long requiredLong(String sql, Object a, Object b, String message) {
        List<Long> values = jdbc.queryForList(sql, Long.class, a, b);
        if (values.isEmpty()) throw new IllegalArgumentException(message);
        return values.getFirst();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Expected numeric database field: " + key);
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static String required(String value, String label, int max) {
        String result = required(value, label);
        if (result.length() > max) throw new IllegalArgumentException(label + " is too long");
        return result;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException("Optional text exceeds maximum length");
        return result;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }

    private static BigDecimal quantity(BigDecimal value, boolean positive) {
        if (value == null || (positive ? value.signum() <= 0 : value.signum() < 0)) {
            throw new IllegalArgumentException(positive ? "Quantity must be positive" : "Quantity cannot be negative");
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("Quantity supports at most four decimal places", invalidScale);
        }
    }

    public record ReceiptCommand(String warehousePublicId, String supplierPublicId,
                                 String supplierAddressPublicId, String receivingLocationPublicId,
                                 String documentNumber, String externalReference, String receiptType,
                                 java.time.LocalDateTime expectedAt, String notes) {}
    public record ReceiptLineCommand(String itemPublicId, BigDecimal expectedQty, String notes) {}
    public record PostReceiptCommand(UUID requestId, List<LineReceiptCommand> lines) {}
    public record LineReceiptCommand(String linePublicId, BigDecimal receivedQty, BigDecimal acceptedQty,
                                     BigDecimal rejectedQty, String rejectionReason,
                                     List<LotAllocation> lots, List<SerialReceipt> serials) {}
    public record LotAllocation(String lotPublicId, BigDecimal quantity) {}
    public record SerialReceipt(String serialNumber, String lotPublicId) {}
    private record Access(long tenantId, long membershipId, String userPublicId) {}
    private record LineUpdate(long lineId, BigDecimal received, BigDecimal accepted,
                              BigDecimal rejected, String status, String rejectionReason) {}
    private record LotLink(long lineId, long itemId, long lotId, BigDecimal quantity) {}
    private record SerialLink(long lineId, long itemId, String serialNumber) {}
}

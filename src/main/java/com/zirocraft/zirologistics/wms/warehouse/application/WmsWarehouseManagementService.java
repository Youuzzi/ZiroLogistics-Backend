package com.zirocraft.zirologistics.wms.warehouse.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("wms")
public class WmsWarehouseManagementService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,99}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Set<String> WAREHOUSE_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "MAINTENANCE", "CLOSED");
    private static final Set<String> LOCATION_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "BLOCKED", "MAINTENANCE");
    private static final Set<String> LOCATION_TYPES = Set.of(
            "ZONE", "RACK", "SHELF", "BIN", "RECEIVING", "STORAGE", "PICKING",
            "PACKING", "SHIPPING", "STAGING", "QUARANTINE", "RETURNS", "DAMAGE", "OTHER");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsWarehouseManagementService(
            WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWarehouses(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_WAREHOUSE_READ");
        return jdbc.queryForList("""
                SELECT public_id, warehouse_code, warehouse_name, description, address_line,
                       city, province, postal_code, country_code, contact_name, contact_phone,
                       contact_email, timezone, status, version, created_at, updated_at
                FROM warehouses
                WHERE tenant_id = ?
                ORDER BY warehouse_code
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getWarehouse(WmsAuthenticatedPrincipal principal, String warehousePublicId) {
        Access access = access(principal, "WMS_WAREHOUSE_READ");
        return publicWarehouse(requireWarehouse(access.tenantId(), warehousePublicId, false));
    }

    @Transactional
    public Map<String, Object> createWarehouse(WmsAuthenticatedPrincipal principal, WarehouseCommand command) {
        Access access = access(principal, "WMS_WAREHOUSE_CREATE");
        Objects.requireNonNull(command, "warehouse command is required");
        lockTenant(access.tenantId());
        WarehouseCommand normalized = normalize(command);
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO warehouses
                      (public_id, tenant_id, warehouse_code, warehouse_name, description, address_line,
                       city, province, postal_code, country_code, contact_name, contact_phone,
                       contact_email, timezone)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    publicId, access.tenantId(), normalized.code(), normalized.name(),
                    normalized.description(), normalized.addressLine(), normalized.city(),
                    normalized.province(), normalized.postalCode(), normalized.countryCode(),
                    normalized.contactName(), normalized.contactPhone(), normalized.contactEmail(),
                    normalized.timezone());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Warehouse code already exists");
        }
        Map<String, Object> after = publicWarehouse(requireWarehouse(access.tenantId(), publicId, false));
        audit(access, "WAREHOUSE_CREATED", publicId, Map.of(), after);
        return after;
    }

    @Transactional
    public Map<String, Object> updateWarehouse(
            WmsAuthenticatedPrincipal principal, String warehousePublicId,
            WarehouseCommand command, long expectedVersion) {
        Access access = access(principal, "WMS_WAREHOUSE_UPDATE");
        Objects.requireNonNull(command, "warehouse command is required");
        lockTenant(access.tenantId());
        Map<String, Object> beforeInternal = requireWarehouse(access.tenantId(), warehousePublicId, true);
        long currentVersion = number(beforeInternal, "version");
        if (currentVersion != expectedVersion) throw new IllegalStateException("Warehouse version conflict");
        if ("CLOSED".equals(beforeInternal.get("status"))) {
            throw new IllegalStateException("Closed warehouse cannot be updated");
        }
        WarehouseCommand normalized = normalize(command);
        try {
            int changed = jdbc.update("""
                    UPDATE warehouses
                    SET warehouse_code = ?, warehouse_name = ?, description = ?, address_line = ?,
                        city = ?, province = ?, postal_code = ?, country_code = ?, contact_name = ?,
                        contact_phone = ?, contact_email = ?, timezone = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND public_id = ? AND version = ?
                    """,
                    normalized.code(), normalized.name(), normalized.description(),
                    normalized.addressLine(), normalized.city(), normalized.province(),
                    normalized.postalCode(), normalized.countryCode(), normalized.contactName(),
                    normalized.contactPhone(), normalized.contactEmail(), normalized.timezone(),
                    access.tenantId(), warehousePublicId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Warehouse version conflict");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Warehouse code already exists");
        }
        Map<String, Object> after = publicWarehouse(requireWarehouse(access.tenantId(), warehousePublicId, false));
        audit(access, "WAREHOUSE_UPDATED", warehousePublicId,
                publicWarehouse(beforeInternal), after);
        return after;
    }

    @Transactional
    public Map<String, Object> changeWarehouseStatus(
            WmsAuthenticatedPrincipal principal, String warehousePublicId,
            String requestedStatus, long expectedVersion) {
        Access access = access(principal, "WMS_WAREHOUSE_STATUS_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> beforeInternal = requireWarehouse(access.tenantId(), warehousePublicId, true);
        String beforeStatus = (String) beforeInternal.get("status");
        String afterStatus = normalizeEnum(requestedStatus, WAREHOUSE_STATUSES, "warehouse status");
        if (number(beforeInternal, "version") != expectedVersion) {
            throw new IllegalStateException("Warehouse version conflict");
        }
        if (!allowedWarehouseTransition(beforeStatus, afterStatus)) {
            throw new IllegalStateException("Warehouse status transition is not allowed");
        }
        if ("CLOSED".equals(afterStatus)) {
            Integer occupied = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ?
                      AND (on_hand_qty > 0 OR reserved_qty > 0)
                    """, Integer.class, access.tenantId(), number(beforeInternal, "id"));
            if (occupied != null && occupied > 0) {
                throw new IllegalStateException("Warehouse with stock cannot be closed");
            }
        }
        int changed = jdbc.update("""
                UPDATE warehouses
                SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND public_id = ? AND version = ?
                """, afterStatus, access.tenantId(), warehousePublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Warehouse version conflict");
        Map<String, Object> after = publicWarehouse(requireWarehouse(access.tenantId(), warehousePublicId, false));
        audit(access, "WAREHOUSE_STATUS_CHANGED", warehousePublicId,
                publicWarehouse(beforeInternal), after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLocations(
            WmsAuthenticatedPrincipal principal, String warehousePublicId) {
        Access access = access(principal, "WMS_LOCATION_READ");
        Map<String, Object> warehouse = requireWarehouse(access.tenantId(), warehousePublicId, false);
        return jdbc.queryForList("""
                SELECT l.public_id, l.location_code, l.location_name, l.location_type, l.description,
                       p.public_id AS parent_location_public_id,
                       l.max_weight_capacity, l.weight_uom, l.max_volume_capacity, l.volume_uom,
                       l.is_pickable, l.is_receivable, l.allow_mixed_skus, l.allow_mixed_lots,
                       l.picking_sequence, l.putaway_sequence, l.status, l.version,
                       l.created_at, l.updated_at
                FROM warehouse_locations l
                LEFT JOIN warehouse_locations p
                  ON p.id = l.parent_location_id AND p.warehouse_id = l.warehouse_id
                     AND p.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.warehouse_id = ?
                ORDER BY l.location_code
                LIMIT 500
                """, access.tenantId(), number(warehouse, "id"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLocation(
            WmsAuthenticatedPrincipal principal, String warehousePublicId, String locationPublicId) {
        Access access = access(principal, "WMS_LOCATION_READ");
        Map<String, Object> warehouse = requireWarehouse(access.tenantId(), warehousePublicId, false);
        return publicLocation(requireLocation(access.tenantId(), number(warehouse, "id"), locationPublicId, false));
    }

    @Transactional
    public Map<String, Object> createLocation(
            WmsAuthenticatedPrincipal principal, String warehousePublicId, LocationCommand command) {
        Access access = access(principal, "WMS_LOCATION_CREATE");
        Objects.requireNonNull(command, "location command is required");
        lockTenant(access.tenantId());
        Map<String, Object> warehouse = requireWarehouse(access.tenantId(), warehousePublicId, true);
        if (!"ACTIVE".equals(warehouse.get("status"))) {
            throw new IllegalStateException("Locations can only be created in an active warehouse");
        }
        LocationCommand normalized = normalize(command);
        Long parentId = resolveParent(access.tenantId(), number(warehouse, "id"),
                normalized.parentLocationPublicId());
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO warehouse_locations
                      (public_id, tenant_id, warehouse_id, parent_location_id, location_code,
                       location_name, location_type, description, max_weight_capacity, weight_uom,
                       max_volume_capacity, volume_uom, is_pickable, is_receivable, allow_mixed_skus,
                       allow_mixed_lots, picking_sequence, putaway_sequence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    publicId, access.tenantId(), number(warehouse, "id"), parentId,
                    normalized.code(), normalized.name(), normalized.type(), normalized.description(),
                    normalized.maxWeightCapacity(), normalized.weightUom(), normalized.maxVolumeCapacity(),
                    normalized.volumeUom(), normalized.pickable(), normalized.receivable(),
                    normalized.allowMixedSkus(), normalized.allowMixedLots(),
                    normalized.pickingSequence(), normalized.putawaySequence());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Location code already exists");
        }
        Map<String, Object> after = publicLocation(requireLocation(
                access.tenantId(), number(warehouse, "id"), publicId, false));
        audit(access, "WAREHOUSE_LOCATION_CREATED", publicId, Map.of(), after);
        return after;
    }

    @Transactional
    public Map<String, Object> updateLocation(
            WmsAuthenticatedPrincipal principal, String warehousePublicId, String locationPublicId,
            LocationCommand command, long expectedVersion) {
        Access access = access(principal, "WMS_LOCATION_UPDATE");
        Objects.requireNonNull(command, "location command is required");
        lockTenant(access.tenantId());
        Map<String, Object> warehouse = requireWarehouse(access.tenantId(), warehousePublicId, true);
        if ("CLOSED".equals(warehouse.get("status"))) {
            throw new IllegalStateException("Closed warehouse locations cannot be updated");
        }
        long warehouseId = number(warehouse, "id");
        Map<String, Object> beforeInternal = requireLocation(access.tenantId(), warehouseId, locationPublicId, true);
        if (number(beforeInternal, "version") != expectedVersion) {
            throw new IllegalStateException("Location version conflict");
        }
        LocationCommand normalized = normalize(command);
        Long parentId = resolveParent(access.tenantId(), warehouseId, normalized.parentLocationPublicId());
        ensureNoCycle(access.tenantId(), warehouseId, number(beforeInternal, "id"), parentId);
        try {
            int changed = jdbc.update("""
                    UPDATE warehouse_locations
                    SET parent_location_id = ?, location_code = ?, location_name = ?, location_type = ?,
                        description = ?, max_weight_capacity = ?, weight_uom = ?, max_volume_capacity = ?,
                        volume_uom = ?, is_pickable = ?, is_receivable = ?, allow_mixed_skus = ?,
                        allow_mixed_lots = ?, picking_sequence = ?, putaway_sequence = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ? AND version = ?
                    """,
                    parentId, normalized.code(), normalized.name(), normalized.type(),
                    normalized.description(), normalized.maxWeightCapacity(), normalized.weightUom(),
                    normalized.maxVolumeCapacity(), normalized.volumeUom(), normalized.pickable(),
                    normalized.receivable(), normalized.allowMixedSkus(), normalized.allowMixedLots(),
                    normalized.pickingSequence(), normalized.putawaySequence(),
                    access.tenantId(), warehouseId, locationPublicId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Location version conflict");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Location code already exists");
        }
        Map<String, Object> after = publicLocation(requireLocation(
                access.tenantId(), warehouseId, locationPublicId, false));
        audit(access, "WAREHOUSE_LOCATION_UPDATED", locationPublicId,
                publicLocation(beforeInternal), after);
        return after;
    }

    @Transactional
    public Map<String, Object> changeLocationStatus(
            WmsAuthenticatedPrincipal principal, String warehousePublicId, String locationPublicId,
            String requestedStatus, long expectedVersion) {
        Access access = access(principal, "WMS_LOCATION_STATUS_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> warehouse = requireWarehouse(access.tenantId(), warehousePublicId, true);
        long warehouseId = number(warehouse, "id");
        Map<String, Object> beforeInternal = requireLocation(access.tenantId(), warehouseId, locationPublicId, true);
        String beforeStatus = (String) beforeInternal.get("status");
        String afterStatus = normalizeEnum(requestedStatus, LOCATION_STATUSES, "location status");
        if (number(beforeInternal, "version") != expectedVersion) {
            throw new IllegalStateException("Location version conflict");
        }
        if (!allowedLocationTransition(beforeStatus, afterStatus)) {
            throw new IllegalStateException("Location status transition is not allowed");
        }
        if (!"ACTIVE".equals(afterStatus)) {
            Integer occupied = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ?
                      AND (on_hand_qty > 0 OR reserved_qty > 0)
                    """, Integer.class, access.tenantId(), warehouseId, number(beforeInternal, "id"));
            if (occupied != null && occupied > 0) {
                throw new IllegalStateException("Location with stock cannot be made unavailable");
            }
        }
        int changed = jdbc.update("""
                UPDATE warehouse_locations
                SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ? AND version = ?
                """, afterStatus, access.tenantId(), warehouseId, locationPublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Location version conflict");
        Map<String, Object> after = publicLocation(requireLocation(
                access.tenantId(), warehouseId, locationPublicId, false));
        audit(access, "WAREHOUSE_LOCATION_STATUS_CHANGED", locationPublicId,
                publicLocation(beforeInternal), after);
        return after;
    }

    private Access access(WmsAuthenticatedPrincipal principal, String permission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(
                principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new WmsMembershipAccessDeniedException();
        }
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required warehouse permission is missing");
        return new Access(resolved.userPublicId(), resolved.tenantId(), resolved.membershipId());
    }

    private void lockTenant(long tenantId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, tenantId);
        if (rows.isEmpty()) throw new AccessDeniedException("Active tenant context is unavailable");
    }

    private Map<String, Object> requireWarehouse(long tenantId, String publicId, boolean lock) {
        if (publicId == null || publicId.isBlank()) throw new WmsWarehouseNotFoundException();
        String sql = """
                SELECT id, public_id, tenant_id, warehouse_code, warehouse_name, description,
                       address_line, city, province, postal_code, country_code, contact_name,
                       contact_phone, contact_email, timezone, status, version, created_at, updated_at
                FROM warehouses WHERE tenant_id = ? AND public_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, publicId);
        if (rows.isEmpty()) throw new WmsWarehouseNotFoundException();
        return rows.getFirst();
    }

    private Map<String, Object> requireLocation(
            long tenantId, long warehouseId, String publicId, boolean lock) {
        if (publicId == null || publicId.isBlank()) throw new WmsWarehouseNotFoundException();
        String sql = """
                SELECT id, public_id, tenant_id, warehouse_id, parent_location_id, location_code,
                       location_name, location_type, description, max_weight_capacity, weight_uom,
                       max_volume_capacity, volume_uom, is_pickable, is_receivable, allow_mixed_skus,
                       allow_mixed_lots, picking_sequence, putaway_sequence, status, version,
                       created_at, updated_at
                FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, warehouseId, publicId);
        if (rows.isEmpty()) throw new WmsWarehouseNotFoundException();
        return rows.getFirst();
    }

    private Long resolveParent(long tenantId, long warehouseId, String parentPublicId) {
        if (parentPublicId == null || parentPublicId.isBlank()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                FOR UPDATE
                """, tenantId, warehouseId, parentPublicId);
        if (rows.isEmpty()) throw new WmsWarehouseNotFoundException();
        Map<String, Object> parent = rows.getFirst();
        if (!"ACTIVE".equals(parent.get("status"))) {
            throw new IllegalArgumentException("Parent location must be active");
        }
        return number(parent, "id");
    }

    private void ensureNoCycle(long tenantId, long warehouseId, long locationId, Long parentId) {
        Set<Long> visited = new HashSet<>();
        Long current = parentId;
        while (current != null) {
            if (current == locationId) throw new IllegalArgumentException("Location hierarchy cannot contain a cycle");
            if (!visited.add(current)) throw new IllegalStateException("Existing location hierarchy is inconsistent");
            List<Long> parents = jdbc.queryForList("""
                    SELECT parent_location_id FROM warehouse_locations
                    WHERE id = ? AND tenant_id = ? AND warehouse_id = ?
                    """, Long.class, current, tenantId, warehouseId);
            if (parents.isEmpty()) throw new WmsWarehouseNotFoundException();
            current = parents.getFirst();
        }
    }

    private void audit(
            Access access, String eventType, String subjectPublicId,
            Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        final String serialized;
        try {
            serialized = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Warehouse audit payload could not be serialized");
        }
        int inserted = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, actor_reference,
                   operation_ref, event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', NULL, ?, ?, ?, ?
                FROM tenants t
                JOIN tenant_memberships m
                  ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u
                  ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType,
                subjectPublicId, access.membershipId(), access.userPublicId(), access.tenantId());
        if (inserted != 1) throw new AccessDeniedException("Audit actor is no longer active");
        // Persist the bounded before/after payload in a second statement so the actor predicate
        // remains the single authorization boundary for all WMS tenant audit writes.
        jdbc.update("""
                UPDATE tenant_audit_events SET change_payload = ?
                WHERE operation_ref = (
                  SELECT operation_ref FROM (
                    SELECT operation_ref FROM tenant_audit_events
                    WHERE subject_public_id = ? AND event_type = ?
                    ORDER BY id DESC LIMIT 1
                  ) latest_event
                )
                """, serialized, subjectPublicId, eventType);
    }

    private static WarehouseCommand normalize(WarehouseCommand c) {
        String code = bounded(c.code(), "warehouse code", 2, 64).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("Warehouse code is invalid");
        String country = bounded(c.countryCode(), "country code", 2, 2).toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches() || !java.util.Arrays.asList(Locale.getISOCountries()).contains(country)) {
            throw new IllegalArgumentException("Country code is invalid");
        }
        String timezone = bounded(c.timezone(), "timezone", 1, 60);
        try { ZoneId.of(timezone); } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Timezone is invalid");
        }
        String email = nullable(c.contactEmail(), "contact email", 254);
        if (email != null && !EMAIL.matcher(email).matches()) throw new IllegalArgumentException("Contact email is invalid");
        return new WarehouseCommand(code, bounded(c.name(), "warehouse name", 2, 150),
                nullable(c.description(), "description", 500), nullable(c.addressLine(), "address", 255),
                nullable(c.city(), "city", 100), nullable(c.province(), "province", 100),
                nullable(c.postalCode(), "postal code", 20), country,
                nullable(c.contactName(), "contact name", 120), nullable(c.contactPhone(), "contact phone", 30),
                email, timezone);
    }

    private static LocationCommand normalize(LocationCommand c) {
        String code = bounded(c.code(), "location code", 2, 100).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("Location code is invalid");
        String type = normalizeEnum(c.type(), LOCATION_TYPES, "location type");
        if (c.pickingSequence() < 0 || c.putawaySequence() < 0) {
            throw new IllegalArgumentException("Location sequence cannot be negative");
        }
        validateCapacity(c.maxWeightCapacity(), c.weightUom(), "weight");
        validateCapacity(c.maxVolumeCapacity(), c.volumeUom(), "volume");
        return new LocationCommand(code, bounded(c.name(), "location name", 2, 150), type,
                nullable(c.description(), "description", 500), c.parentLocationPublicId(),
                c.maxWeightCapacity(), normalizeUom(c.weightUom()), c.maxVolumeCapacity(),
                normalizeUom(c.volumeUom()), Boolean.TRUE.equals(c.pickable()),
                Boolean.TRUE.equals(c.receivable()), Boolean.TRUE.equals(c.allowMixedSkus()),
                Boolean.TRUE.equals(c.allowMixedLots()), c.pickingSequence(), c.putawaySequence());
    }

    private static void validateCapacity(BigDecimal value, String uom, String label) {
        String normalizedUom = normalizeUom(uom);
        if (value == null && normalizedUom != null || value != null && normalizedUom == null) {
            throw new IllegalArgumentException(label + " capacity and UOM must be supplied together");
        }
        if (value != null && (value.signum() < 0 || value.precision() > 19 || value.scale() > 4)) {
            throw new IllegalArgumentException(label + " capacity is invalid");
        }
    }

    private static String normalizeUom(String value) {
        return value == null || value.isBlank() ? null : bounded(value, "UOM code", 1, 10).toUpperCase(Locale.ROOT);
    }

    private static String bounded(String value, String label, int min, int max) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(label + " length is invalid");
        }
        return normalized;
    }

    private static String nullable(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, label, 0, max);
    }

    private static String normalizeEnum(String value, Set<String> allowed, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }

    private static boolean allowedWarehouseTransition(String before, String after) {
        if (Objects.equals(before, after)) return true;
        return switch (before) {
            case "ACTIVE" -> Set.of("INACTIVE", "MAINTENANCE", "CLOSED").contains(after);
            case "INACTIVE" -> Set.of("ACTIVE", "CLOSED").contains(after);
            case "MAINTENANCE" -> Set.of("ACTIVE", "INACTIVE", "CLOSED").contains(after);
            default -> false;
        };
    }

    private static boolean allowedLocationTransition(String before, String after) {
        if (Objects.equals(before, after)) return true;
        return switch (before) {
            case "ACTIVE" -> Set.of("INACTIVE", "BLOCKED", "MAINTENANCE").contains(after);
            case "INACTIVE" -> Set.of("ACTIVE", "BLOCKED", "MAINTENANCE").contains(after);
            case "BLOCKED" -> Set.of("ACTIVE", "MAINTENANCE").contains(after);
            case "MAINTENANCE" -> Set.of("ACTIVE", "INACTIVE", "BLOCKED").contains(after);
            default -> false;
        };
    }

    private static Map<String, Object> publicWarehouse(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("id");
        result.remove("tenant_id");
        return result;
    }

    private static Map<String, Object> publicLocation(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("id");
        result.remove("tenant_id");
        result.remove("warehouse_id");
        result.remove("parent_location_id");
        return result;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public record WarehouseCommand(
            String code, String name, String description, String addressLine, String city,
            String province, String postalCode, String countryCode, String contactName,
            String contactPhone, String contactEmail, String timezone) {
    }

    public record LocationCommand(
            String code, String name, String type, String description, String parentLocationPublicId,
            BigDecimal maxWeightCapacity, String weightUom, BigDecimal maxVolumeCapacity,
            String volumeUom, Boolean pickable, Boolean receivable, Boolean allowMixedSkus,
            Boolean allowMixedLots, int pickingSequence, int putawaySequence) {
    }

    private record Access(String userPublicId, long tenantId, long membershipId) {
    }
}

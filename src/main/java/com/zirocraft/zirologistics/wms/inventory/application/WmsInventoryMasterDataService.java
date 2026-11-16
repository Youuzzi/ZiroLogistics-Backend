package com.zirocraft.zirologistics.wms.inventory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant-scoped item/category master data and global read-only UOM catalogue.
 * Inventory quantity mutations are intentionally not exposed by this service.
 */
@Service
@Profile("wms")
public class WmsInventoryMasterDataService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,79}");
    private static final Set<String> CATEGORY_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> ITEM_STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");
    private static final Set<String> TRACKING_METHODS = Set.of("NONE", "LOT", "SERIAL", "LOT_AND_SERIAL");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsInventoryMasterDataService(
            WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUoms(WmsAuthenticatedPrincipal principal, String dimension) {
        access(principal, "WMS_UOM_CATALOG_READ");
        if (dimension == null || dimension.isBlank()) {
            return jdbc.queryForList("""
                    SELECT uom_code, uom_name, dimension, decimal_places
                    FROM units_of_measure WHERE is_active = 1
                    ORDER BY dimension, uom_code LIMIT 200
                    """);
        }
        String normalized = enumValue(dimension, Set.of("COUNT", "MASS", "LENGTH", "AREA", "VOLUME", "TIME"),
                "UOM dimension");
        return jdbc.queryForList("""
                SELECT uom_code, uom_name, dimension, decimal_places
                FROM units_of_measure WHERE is_active = 1 AND dimension = ?
                ORDER BY uom_code LIMIT 200
                """, normalized);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCategories(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_ITEM_CATEGORY_READ");
        return jdbc.queryForList("""
                SELECT c.public_id, c.category_code, c.category_name, c.description,
                       p.public_id AS parent_category_public_id, c.status, c.version,
                       c.created_at, c.updated_at
                FROM item_categories c
                LEFT JOIN item_categories p
                  ON p.id = c.parent_category_id AND p.tenant_id = c.tenant_id
                WHERE c.tenant_id = ?
                ORDER BY c.category_code LIMIT 500
                """, access.tenantId());
    }

    @Transactional
    public Map<String, Object> createCategory(
            WmsAuthenticatedPrincipal principal, CategoryCommand command) {
        Access access = access(principal, "WMS_ITEM_CATEGORY_MANAGE");
        Objects.requireNonNull(command, "category command is required");
        lockTenant(access.tenantId());
        String code = normalizeCode(command.code(), "category code");
        String name = bounded(command.name(), "category name", 2, 150);
        String description = optional(command.description(), 500);
        Long parentId = resolveCategoryParent(access.tenantId(), command.parentCategoryPublicId());
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO item_categories
                      (public_id, tenant_id, parent_category_id, category_code, category_name, description)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, publicId, access.tenantId(), parentId, code, name, description);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Category code already exists");
        }
        Map<String, Object> after = categoryByPublicId(access.tenantId(), publicId);
        audit(access, "ITEM_CATEGORY_CREATED", publicId, Map.of(), after);
        return publicCategory(after);
    }

    @Transactional
    public Map<String, Object> updateCategory(
            WmsAuthenticatedPrincipal principal, String categoryPublicId,
            CategoryCommand command, long expectedVersion) {
        Access access = access(principal, "WMS_ITEM_CATEGORY_MANAGE");
        Objects.requireNonNull(command, "category command is required");
        lockTenant(access.tenantId());
        Map<String, Object> before = categoryByPublicId(access.tenantId(), categoryPublicId);
        if (number(before, "version") != expectedVersion) throw new IllegalStateException("Category version conflict");
        String code = normalizeCode(command.code(), "category code");
        String name = bounded(command.name(), "category name", 2, 150);
        String description = optional(command.description(), 500);
        Long parentId = resolveCategoryParent(access.tenantId(), command.parentCategoryPublicId());
        ensureNoCategoryCycle(access.tenantId(), number(before, "id"), parentId);
        try {
            int changed = jdbc.update("""
                    UPDATE item_categories
                    SET parent_category_id = ?, category_code = ?, category_name = ?, description = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND public_id = ? AND version = ?
                    """, parentId, code, name, description, access.tenantId(), categoryPublicId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Category version conflict");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Category code already exists");
        }
        Map<String, Object> after = categoryByPublicId(access.tenantId(), categoryPublicId);
        audit(access, "ITEM_CATEGORY_UPDATED", categoryPublicId, publicCategory(before), publicCategory(after));
        return publicCategory(after);
    }

    @Transactional
    public Map<String, Object> changeCategoryStatus(
            WmsAuthenticatedPrincipal principal, String categoryPublicId,
            String requestedStatus, long expectedVersion) {
        Access access = access(principal, "WMS_ITEM_CATEGORY_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> before = categoryByPublicId(access.tenantId(), categoryPublicId);
        if (number(before, "version") != expectedVersion) throw new IllegalStateException("Category version conflict");
        String status = enumValue(requestedStatus, CATEGORY_STATUSES, "category status");
        if (Objects.equals(before.get("status"), status)) return publicCategory(before);
        if ("INACTIVE".equals(status)) {
            Integer activeChildren = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM item_categories
                    WHERE tenant_id = ? AND parent_category_id = ? AND status = 'ACTIVE'
                    """, Integer.class, access.tenantId(), number(before, "id"));
            Integer activeItems = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM items WHERE tenant_id = ? AND category_id = ? AND status = 'ACTIVE'
                    """, Integer.class, access.tenantId(), number(before, "id"));
            if ((activeChildren != null && activeChildren > 0) || (activeItems != null && activeItems > 0)) {
                throw new IllegalStateException("Category with active children or items cannot be deactivated");
            }
        }
        int changed = jdbc.update("""
                UPDATE item_categories SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND public_id = ? AND version = ?
                """, status, access.tenantId(), categoryPublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Category version conflict");
        Map<String, Object> after = categoryByPublicId(access.tenantId(), categoryPublicId);
        audit(access, "ITEM_CATEGORY_STATUS_CHANGED", categoryPublicId,
                publicCategory(before), publicCategory(after));
        return publicCategory(after);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listItems(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_ITEM_READ");
        return jdbc.queryForList("""
                SELECT i.public_id, i.sku, i.item_name, i.description,
                       c.public_id AS category_public_id, c.category_name,
                       u.uom_code AS base_uom_code, u.uom_name AS base_uom_name,
                       i.tracking_method, i.is_stock_item, i.is_purchasable, i.is_sellable,
                       i.minimum_stock_level, i.status, i.version, i.created_at, i.updated_at
                FROM items i
                JOIN units_of_measure u ON u.id = i.base_uom_id
                LEFT JOIN item_categories c ON c.id = i.category_id AND c.tenant_id = i.tenant_id
                WHERE i.tenant_id = ?
                ORDER BY i.sku LIMIT 500
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getItem(WmsAuthenticatedPrincipal principal, String itemPublicId) {
        Access access = access(principal, "WMS_ITEM_READ");
        return publicItem(requireItem(access.tenantId(), itemPublicId));
    }

    @Transactional
    public Map<String, Object> createItem(WmsAuthenticatedPrincipal principal, ItemCommand command) {
        Access access = access(principal, "WMS_ITEM_CREATE");
        Objects.requireNonNull(command, "item command is required");
        lockTenant(access.tenantId());
        NormalizedItem item = normalize(command);
        Long categoryId = resolveActiveCategory(access.tenantId(), command.categoryPublicId());
        Long baseUomId = resolveActiveUom(item.baseUomCode());
        if (!item.stockItem() && !item.trackingMethod().equals("NONE")) {
            throw new IllegalArgumentException("Non-stock items cannot use lot or serial tracking");
        }
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO items
                      (public_id, tenant_id, category_id, sku, item_name, description, base_uom_id,
                       tracking_method, is_stock_item, is_purchasable, is_sellable, minimum_stock_level)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, publicId, access.tenantId(), categoryId, item.sku(), item.name(),
                    item.description(), baseUomId, item.trackingMethod(), item.stockItem(),
                    item.purchasable(), item.sellable(), item.minimumStock());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Item SKU already exists");
        }
        Map<String, Object> after = requireItem(access.tenantId(), publicId);
        audit(access, "ITEM_CREATED", publicId, Map.of(), publicItem(after));
        return publicItem(after);
    }

    @Transactional
    public Map<String, Object> updateItem(
            WmsAuthenticatedPrincipal principal, String itemPublicId,
            ItemCommand command, long expectedVersion) {
        Access access = access(principal, "WMS_ITEM_UPDATE");
        Objects.requireNonNull(command, "item command is required");
        lockTenant(access.tenantId());
        Map<String, Object> before = requireItem(access.tenantId(), itemPublicId);
        if (number(before, "version") != expectedVersion) throw new IllegalStateException("Item version conflict");
        NormalizedItem item = normalize(command);
        Long categoryId = resolveActiveCategory(access.tenantId(), command.categoryPublicId());
        Long baseUomId = resolveActiveUom(item.baseUomCode());
        boolean stockDefinitionChanged = !Objects.equals(before.get("base_uom_code"), item.baseUomCode())
                || !Objects.equals(before.get("tracking_method"), item.trackingMethod())
                || !Objects.equals(bool(before.get("is_stock_item")), item.stockItem());
        if (stockDefinitionChanged && hasInventoryHistory(access.tenantId(), number(before, "id"))) {
            throw new IllegalStateException("Stock-defining item attributes are immutable after inventory history exists");
        }
        if (!item.stockItem() && !item.trackingMethod().equals("NONE")) {
            throw new IllegalArgumentException("Non-stock items cannot use lot or serial tracking");
        }
        try {
            int changed = jdbc.update("""
                    UPDATE items
                    SET category_id = ?, sku = ?, item_name = ?, description = ?, base_uom_id = ?,
                        tracking_method = ?, is_stock_item = ?, is_purchasable = ?, is_sellable = ?,
                        minimum_stock_level = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND public_id = ? AND version = ?
                    """, categoryId, item.sku(), item.name(), item.description(), baseUomId,
                    item.trackingMethod(), item.stockItem(), item.purchasable(), item.sellable(),
                    item.minimumStock(), access.tenantId(), itemPublicId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Item version conflict");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Item SKU already exists");
        }
        Map<String, Object> after = requireItem(access.tenantId(), itemPublicId);
        audit(access, "ITEM_UPDATED", itemPublicId, publicItem(before), publicItem(after));
        return publicItem(after);
    }

    @Transactional
    public Map<String, Object> changeItemStatus(
            WmsAuthenticatedPrincipal principal, String itemPublicId,
            String requestedStatus, long expectedVersion) {
        Access access = access(principal, "WMS_ITEM_STATUS_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> before = requireItem(access.tenantId(), itemPublicId);
        if (number(before, "version") != expectedVersion) throw new IllegalStateException("Item version conflict");
        String status = enumValue(requestedStatus, ITEM_STATUSES, "item status");
        if (Objects.equals(before.get("status"), status)) return publicItem(before);
        if (!"ACTIVE".equals(status)) {
            Integer occupied = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM inventory_balances
                    WHERE tenant_id = ? AND item_id = ? AND (on_hand_qty > 0 OR reserved_qty > 0)
                    """, Integer.class, access.tenantId(), number(before, "id"));
            if (occupied != null && occupied > 0) {
                throw new IllegalStateException("Item with on-hand or reserved stock cannot be deactivated");
            }
        }
        int changed = jdbc.update("""
                UPDATE items SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND public_id = ? AND version = ?
                """, status, access.tenantId(), itemPublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Item version conflict");
        Map<String, Object> after = requireItem(access.tenantId(), itemPublicId);
        audit(access, "ITEM_STATUS_CHANGED", itemPublicId, publicItem(before), publicItem(after));
        return publicItem(after);
    }


    @Transactional(readOnly = true)
    public List<Map<String, Object>> listIdentifiers(WmsAuthenticatedPrincipal principal, String itemPublicId) {
        Access access = access(principal, "WMS_ITEM_READ");
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        return jdbc.queryForList("""
                SELECT public_id, identifier_type, identifier_value, is_primary, created_at
                FROM item_identifiers
                WHERE tenant_id = ? AND item_id = ?
                ORDER BY is_primary DESC, identifier_type, identifier_value
                LIMIT 200
                """, access.tenantId(), number(item, "id"));
    }

    @Transactional
    public Map<String, Object> addIdentifier(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                              IdentifierCommand command) {
        Access access = access(principal, "WMS_ITEM_IDENTIFIER_MANAGE");
        Objects.requireNonNull(command, "identifier command is required");
        lockTenant(access.tenantId());
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        String type = enumValue(command.type(), Set.of("BARCODE", "QR_CODE", "GTIN", "ALTERNATE_SKU"),
                "identifier type");
        String value = normalizeIdentifier(type, command.value());
        boolean primary = Boolean.TRUE.equals(command.primary());
        if (primary) clearPrimaryIdentifier(access.tenantId(), number(item, "id"));
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO item_identifiers
                      (public_id, tenant_id, item_id, identifier_type, identifier_value, is_primary)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, publicId, access.tenantId(), number(item, "id"), type, value, primary);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Identifier already exists for this tenant and type");
        }
        Map<String, Object> after = identifierByPublicId(access.tenantId(), number(item, "id"), publicId);
        audit(access, "ITEM_IDENTIFIER_ADDED", publicId, Map.of(), after);
        return after;
    }

    @Transactional
    public Map<String, Object> updateIdentifier(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                                 String identifierPublicId, IdentifierCommand command) {
        Access access = access(principal, "WMS_ITEM_IDENTIFIER_MANAGE");
        Objects.requireNonNull(command, "identifier command is required");
        lockTenant(access.tenantId());
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        long itemId = number(item, "id");
        Map<String, Object> before = identifierByPublicId(access.tenantId(), itemId, identifierPublicId);
        String type = enumValue(command.type(), Set.of("BARCODE", "QR_CODE", "GTIN", "ALTERNATE_SKU"),
                "identifier type");
        String value = normalizeIdentifier(type, command.value());
        boolean primary = Boolean.TRUE.equals(command.primary());
        if (primary) clearPrimaryIdentifier(access.tenantId(), itemId);
        try {
            int changed = jdbc.update("""
                    UPDATE item_identifiers
                    SET identifier_type = ?, identifier_value = ?, is_primary = ?
                    WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                    """, type, value, primary, access.tenantId(), itemId, identifierPublicId);
            if (changed != 1) throw new IllegalStateException("Identifier not found");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Identifier already exists for this tenant and type");
        }
        Map<String, Object> after = identifierByPublicId(access.tenantId(), itemId, identifierPublicId);
        audit(access, "ITEM_IDENTIFIER_UPDATED", identifierPublicId, before, after);
        return after;
    }

    @Transactional
    public void removeIdentifier(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                 String identifierPublicId) {
        Access access = access(principal, "WMS_ITEM_IDENTIFIER_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        long itemId = number(item, "id");
        Map<String, Object> before = identifierByPublicId(access.tenantId(), itemId, identifierPublicId);
        int changed = jdbc.update("""
                DELETE FROM item_identifiers
                WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                """, access.tenantId(), itemId, identifierPublicId);
        if (changed != 1) throw new IllegalStateException("Identifier not found");
        audit(access, "ITEM_IDENTIFIER_REMOVED", identifierPublicId, before, Map.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupItemByIdentifier(WmsAuthenticatedPrincipal principal,
                                                       String identifierType, String identifierValue) {
        Access access = access(principal, "WMS_ITEM_READ");
        String type = enumValue(identifierType, Set.of("BARCODE", "QR_CODE", "GTIN", "ALTERNATE_SKU"),
                "identifier type");
        String value = normalizeIdentifier(type, identifierValue);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.public_id, i.sku, i.item_name, i.status, i.tracking_method,
                       i.is_stock_item, u.uom_code AS base_uom_code
                FROM item_identifiers x
                JOIN items i ON i.id = x.item_id AND i.tenant_id = x.tenant_id
                JOIN units_of_measure u ON u.id = i.base_uom_id
                WHERE x.tenant_id = ? AND x.identifier_type = ? AND x.identifier_value = ?
                  AND i.status = 'ACTIVE'
                LIMIT 2
                """, access.tenantId(), type, value);
        if (rows.isEmpty()) throw new IllegalArgumentException("Active item was not found");
        if (rows.size() > 1) throw new IllegalStateException("Identifier uniqueness invariant is violated");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUomConversions(WmsAuthenticatedPrincipal principal, String itemPublicId) {
        Access access = access(principal, "WMS_ITEM_READ");
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        return jdbc.queryForList("""
                SELECT f.uom_code AS from_uom_code, t.uom_code AS to_uom_code,
                       c.conversion_factor, c.is_active, c.created_at, c.updated_at
                FROM item_uom_conversions c
                JOIN units_of_measure f ON f.id = c.from_uom_id
                JOIN units_of_measure t ON t.id = c.to_uom_id
                WHERE c.tenant_id = ? AND c.item_id = ?
                ORDER BY f.uom_code, t.uom_code
                LIMIT 200
                """, access.tenantId(), number(item, "id"));
    }

    @Transactional
    public Map<String, Object> addUomConversion(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                                 UomConversionCommand command) {
        Access access = access(principal, "WMS_ITEM_UOM_CONVERSION_MANAGE");
        Objects.requireNonNull(command, "UOM conversion command is required");
        lockTenant(access.tenantId());
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        String fromCode = normalizeUomCode(command.fromUomCode());
        String toCode = normalizeUomCode(command.toUomCode());
        if (fromCode.equals(toCode)) throw new IllegalArgumentException("Conversion UOMs must be different");
        BigDecimal factor = command.factor();
        if (factor == null || factor.signum() <= 0 || factor.scale() > 8 || factor.precision() > 19) {
            throw new IllegalArgumentException("Conversion factor must be positive and fit DECIMAL(19,8)");
        }
        Map<String, Object> from = activeUom(fromCode);
        Map<String, Object> to = activeUom(toCode);
        Map<String, Object> base = activeUom((String) item.get("base_uom_code"));
        String dimension = (String) from.get("dimension");
        if (!dimension.equals(to.get("dimension")) || !dimension.equals(base.get("dimension"))) {
            throw new IllegalArgumentException("Item UOM conversions must use the item's base dimension");
        }
        try {
            jdbc.update("""
                    INSERT INTO item_uom_conversions
                      (tenant_id, item_id, from_uom_id, to_uom_id, conversion_factor, is_active)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, access.tenantId(), number(item, "id"), number(from, "id"), number(to, "id"), factor);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("UOM conversion pair already exists; deactivate it before replacement");
        }
        Map<String, Object> after = conversionByCodes(access.tenantId(), number(item, "id"), fromCode, toCode);
        audit(access, "ITEM_UOM_CONVERSION_CREATED",
                itemPublicId + ":" + fromCode + ":" + toCode, Map.of(), after);
        return after;
    }

    @Transactional
    public Map<String, Object> deactivateUomConversion(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                                        String fromUomCode, String toUomCode) {
        Access access = access(principal, "WMS_ITEM_UOM_CONVERSION_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> item = requireItem(access.tenantId(), itemPublicId);
        String fromCode = normalizeUomCode(fromUomCode);
        String toCode = normalizeUomCode(toUomCode);
        Map<String, Object> before = conversionByCodes(access.tenantId(), number(item, "id"), fromCode, toCode);
        if (bool(before.get("is_active"))) {
            jdbc.update("""
                    UPDATE item_uom_conversions SET is_active = 0, updated_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND item_id = ?
                      AND from_uom_id = (SELECT id FROM units_of_measure WHERE uom_code = ?)
                      AND to_uom_id = (SELECT id FROM units_of_measure WHERE uom_code = ?)
                    """, access.tenantId(), number(item, "id"), fromCode, toCode);
        }
        Map<String, Object> after = conversionByCodes(access.tenantId(), number(item, "id"), fromCode, toCode);
        audit(access, "ITEM_UOM_CONVERSION_DEACTIVATED",
                itemPublicId + ":" + fromCode + ":" + toCode, before, after);
        return after;
    }

    private void clearPrimaryIdentifier(Long tenantId, long itemId) {
        jdbc.update("UPDATE item_identifiers SET is_primary = 0 WHERE tenant_id = ? AND item_id = ? AND is_primary = 1",
                tenantId, itemId);
    }

    private Map<String, Object> identifierByPublicId(Long tenantId, long itemId, String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT public_id, identifier_type, identifier_value, is_primary, created_at
                FROM item_identifiers WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                """, tenantId, itemId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Identifier not found");
        return rows.getFirst();
    }

    private static String normalizeIdentifier(String type, String value) {
        String normalized = required(value, "identifier value");
        if (normalized.length() > 150 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identifier value is invalid");
        }
        if ("GTIN".equals(type) && !normalized.matches("(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})")) {
            throw new IllegalArgumentException("GTIN must contain 8, 12, 13 or 14 digits");
        }
        if ("ALTERNATE_SKU".equals(type)) return normalizeCode(normalized, "alternate SKU");
        return normalized;
    }

    private static String normalizeUomCode(String value) {
        String normalized = required(value, "UOM code").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,20}")) throw new IllegalArgumentException("UOM code is invalid");
        return normalized;
    }

    private Map<String, Object> activeUom(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, uom_code, dimension, decimal_places
                FROM units_of_measure WHERE uom_code = ? AND is_active = 1
                """, code);
        if (rows.isEmpty()) throw new IllegalArgumentException("Active UOM was not found");
        return rows.getFirst();
    }

    private Map<String, Object> conversionByCodes(Long tenantId, long itemId, String fromCode, String toCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.uom_code AS from_uom_code, t.uom_code AS to_uom_code,
                       c.conversion_factor, c.is_active, c.created_at, c.updated_at
                FROM item_uom_conversions c
                JOIN units_of_measure f ON f.id = c.from_uom_id
                JOIN units_of_measure t ON t.id = c.to_uom_id
                WHERE c.tenant_id = ? AND c.item_id = ?
                  AND f.uom_code = ? AND t.uom_code = ?
                """, tenantId, itemId, fromCode, toCode);
        if (rows.isEmpty()) throw new IllegalArgumentException("UOM conversion not found");
        return rows.getFirst();
    }

    private Access access(WmsAuthenticatedPrincipal principal, String permission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())
                || !Objects.equals(resolved.userPublicId(), principal.userPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Permission denied");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId(),
                resolved.permissionCodes());
    }

    private void lockTenant(Long tenantId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", Long.class, tenantId);
        if (rows.isEmpty()) throw new AccessDeniedException("Tenant is unavailable");
    }

    private Long resolveCategoryParent(Long tenantId, String publicId) {
        if (publicId == null || publicId.isBlank()) return null;
        List<Long> rows = jdbc.queryForList("""
                SELECT id FROM item_categories
                WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE'
                """, Long.class, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Parent category not found or inactive");
        return rows.getFirst();
    }

    private void ensureNoCategoryCycle(Long tenantId, long categoryId, Long parentId) {
        if (parentId == null) return;
        if (parentId == categoryId) throw new IllegalArgumentException("Category cannot be its own parent");
        Integer cycle = jdbc.queryForObject("""
                WITH RECURSIVE descendants AS (
                  SELECT id, parent_category_id FROM item_categories
                  WHERE tenant_id = ? AND id = ?
                  UNION ALL
                  SELECT c.id, c.parent_category_id FROM item_categories c
                  JOIN descendants d ON c.parent_category_id = d.id
                  WHERE c.tenant_id = ?
                )
                SELECT COUNT(*) FROM descendants WHERE id = ?
                """, Integer.class, tenantId, categoryId, tenantId, parentId);
        if (cycle != null && cycle > 0) throw new IllegalArgumentException("Category hierarchy cycle is not allowed");
    }

    private Long resolveActiveCategory(Long tenantId, String publicId) {
        if (publicId == null || publicId.isBlank()) return null;
        List<Long> rows = jdbc.queryForList("""
                SELECT id FROM item_categories
                WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE'
                """, Long.class, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Category not found or inactive");
        return rows.getFirst();
    }

    private Long resolveActiveUom(String code) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM units_of_measure WHERE uom_code = ? AND is_active = 1", Long.class, code);
        if (rows.isEmpty()) throw new IllegalArgumentException("Base UOM not found or inactive");
        return rows.getFirst();
    }

    private Map<String, Object> categoryByPublicId(Long tenantId, String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.public_id, c.category_code, c.category_name, c.description,
                       p.public_id AS parent_category_public_id, c.status, c.version,
                       c.created_at, c.updated_at
                FROM item_categories c
                LEFT JOIN item_categories p ON p.id = c.parent_category_id AND p.tenant_id = c.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Category not found");
        return rows.getFirst();
    }

    private Map<String, Object> requireItem(Long tenantId, String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.public_id, i.sku, i.item_name, i.description,
                       c.public_id AS category_public_id, c.category_name,
                       u.uom_code AS base_uom_code, u.uom_name AS base_uom_name,
                       i.tracking_method, i.is_stock_item, i.is_purchasable, i.is_sellable,
                       i.minimum_stock_level, i.status, i.version, i.created_at, i.updated_at
                FROM items i
                JOIN units_of_measure u ON u.id = i.base_uom_id
                LEFT JOIN item_categories c ON c.id = i.category_id AND c.tenant_id = i.tenant_id
                WHERE i.tenant_id = ? AND i.public_id = ?
                """, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Item not found");
        return rows.getFirst();
    }

    private boolean hasInventoryHistory(Long tenantId, long itemId) {
        Integer count = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ? AND item_id = ?)
                  +
                  (SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ? AND item_id = ?)
                """, Integer.class, tenantId, itemId, tenantId, itemId);
        return count != null && count > 0;
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
            throw new IllegalStateException("Audit event serialization failed");
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

    private static Map<String, Object> publicCategory(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        return result;
    }

    private static Map<String, Object> publicItem(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        return result;
    }

    private static NormalizedItem normalize(ItemCommand command) {
        String sku = normalizeCode(command.sku(), "SKU");
        String name = bounded(command.name(), "item name", 2, 200);
        String description = optional(command.description(), 4000);
        String baseUom = required(command.baseUomCode(), "base UOM").toUpperCase(Locale.ROOT);
        if (!baseUom.matches("[A-Z0-9_-]{1,20}")) throw new IllegalArgumentException("Base UOM code is invalid");
        String tracking = enumValue(command.trackingMethod(), TRACKING_METHODS, "tracking method");
        BigDecimal minimum = command.minimumStock() == null ? BigDecimal.ZERO : command.minimumStock();
        if (minimum.signum() < 0 || minimum.scale() > 4 || minimum.precision() > 19) {
            throw new IllegalArgumentException("Minimum stock quantity is invalid");
        }
        return new NormalizedItem(sku, name, description, baseUom, tracking,
                Boolean.TRUE.equals(command.stockItem()), Boolean.TRUE.equals(command.purchasable()),
                Boolean.TRUE.equals(command.sellable()), minimum);
    }

    private static String normalizeCode(String value, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String bounded(String value, String field, int min, int max) {
        String normalized = required(value, field);
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Optional text is too long");
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String enumValue(String value, Set<String> allowed, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    public record CategoryCommand(String code, String name, String description, String parentCategoryPublicId) {}
    public record IdentifierCommand(String type, String value, Boolean primary) {}
    public record UomConversionCommand(String fromUomCode, String toUomCode, BigDecimal factor) {}
    public record ItemCommand(
            String sku, String name, String description, String categoryPublicId,
            String baseUomCode, String trackingMethod, Boolean stockItem, Boolean purchasable,
            Boolean sellable, BigDecimal minimumStock) {}
    private record Access(Long tenantId, Long membershipId, String userPublicId, Set<String> permissions) {}
    private record NormalizedItem(
            String sku, String name, String description, String baseUomCode, String trackingMethod,
            boolean stockItem, boolean purchasable, boolean sellable, BigDecimal minimumStock) {}
}

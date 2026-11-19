package com.zirocraft.zirologistics.wms.authorization.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class WmsRoleManagementService {
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private static final Set<String> DELEGABLE_PERMISSIONS = Set.of(
            "WMS_ROLE_READ", "WMS_ROLE_CREATE", "WMS_ROLE_UPDATE",
            "WMS_ROLE_DEACTIVATE", "WMS_PERMISSION_CATALOG_READ",
            "WMS_MEMBERSHIP_ROLE_READ",
            "WMS_WAREHOUSE_READ", "WMS_WAREHOUSE_CREATE", "WMS_WAREHOUSE_UPDATE",
            "WMS_LOCATION_READ", "WMS_LOCATION_CREATE", "WMS_LOCATION_UPDATE",
            "WMS_ITEM_READ", "WMS_ITEM_CREATE", "WMS_ITEM_UPDATE",
            "WMS_ITEM_CATEGORY_READ", "WMS_ITEM_CATEGORY_MANAGE",
            "WMS_UOM_CATALOG_READ", "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ");
    private static final Map<String, String> AUDIT_TYPES = Map.of(
            "CREATE", "ROLE_CREATED",
            "UPDATE", "ROLE_UPDATED",
            "DEACTIVATE", "ROLE_DEACTIVATED",
            "PERMISSIONS", "ROLE_PERMISSIONS_REPLACED",
            "ASSIGN", "MEMBERSHIP_ROLE_ASSIGNED",
            "REMOVE", "MEMBERSHIP_ROLE_REMOVED");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsRoleManagementService(
            WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles(WmsAuthenticatedPrincipal principal) {
        return listRoles(principal, 0, 200);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles(WmsAuthenticatedPrincipal principal, int page, int size) {
        Access access = access(principal, "WMS_ROLE_READ");
        long offset = boundedOffset(page, size);
        return jdbc.queryForList("""
                SELECT public_id, role_code, role_name, description, is_system_role, status, version
                FROM roles WHERE tenant_id = ? ORDER BY is_system_role DESC, role_name, role_code
                LIMIT ? OFFSET ?
                """, access.tenantId(), size, offset);
    }

    private static long boundedOffset(int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid page or size");
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Page is too large");
        return offset;
    }
    @Transactional
    public Map<String, Object> createRole(
            WmsAuthenticatedPrincipal principal, String code, String name, String description) {
        Access access = access(principal, "WMS_ROLE_CREATE");
        lockTenant(access.tenantId());
        String normalizedCode = normalizeCode(code);
        String normalizedName = bounded(name, "role name", 2, 120);
        String normalizedDescription = description == null ? null : bounded(description, "description", 0, 255);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE tenant_id = ? AND role_code = ?",
                Integer.class, access.tenantId(), normalizedCode) > 0) {
            throw new IllegalStateException("Role code already exists");
        }
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, ?, ?, ?, 0, 'ACTIVE')
                """, publicId, access.tenantId(), normalizedCode, normalizedName, normalizedDescription);
        Map<String, Object> after = roleByPublicId(access.tenantId(), publicId);
        audit(access, "CREATE", publicId, Map.of(), after);
        return roleResponse(after);
    }

    @Transactional
    public Map<String, Object> updateRole(
            WmsAuthenticatedPrincipal principal, String rolePublicId, String name,
            String description, long expectedVersion) {
        Access access = access(principal, "WMS_ROLE_UPDATE");
        lockTenant(access.tenantId());
        Map<String, Object> before = requireRole(access.tenantId(), rolePublicId);
        if (number(before, "is_system_role") != 0) {
            throw new AccessDeniedException("System roles cannot be changed");
        }
        if (number(before, "version") != expectedVersion) {
            throw new IllegalStateException("Role version conflict");
        }
        String normalizedName = bounded(name, "role name", 2, 120);
        String normalizedDescription = description == null ? null : bounded(description, "description", 0, 255);
        int changed = jdbc.update("""
                UPDATE roles SET role_name = ?, description = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND public_id = ? AND is_system_role = 0 AND version = ?
                """, normalizedName, normalizedDescription, access.tenantId(), rolePublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Role version conflict");
        Map<String, Object> after = roleByPublicId(access.tenantId(), rolePublicId);
        audit(access, "UPDATE", rolePublicId, before, after);
        return roleResponse(after);
    }

    @Transactional
    public void deactivateRole(WmsAuthenticatedPrincipal principal, String rolePublicId, long expectedVersion) {
        Access access = access(principal, "WMS_ROLE_DEACTIVATE");
        lockTenant(access.tenantId());
        Map<String, Object> before = requireRole(access.tenantId(), rolePublicId);
        if (number(before, "is_system_role") != 0
                || "TENANT_OWNER".equals(before.get("role_code"))) {
            throw new AccessDeniedException("System and owner roles cannot be deactivated");
        }
        if (number(before, "version") != expectedVersion) throw new IllegalStateException("Role version conflict");
        int changed = jdbc.update("""
                UPDATE roles SET status = 'INACTIVE', version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE' AND version = ?
                """, access.tenantId(), rolePublicId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Role version conflict");
        audit(access, "DEACTIVATE", rolePublicId, before,
                Map.of("status", "INACTIVE", "version", expectedVersion + 1));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> permissionCatalogue(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_PERMISSION_CATALOG_READ");
        return jdbc.queryForList("""
                SELECT permission_code, module, description
                FROM permissions WHERE permission_code IN (
                  'WMS_ROLE_READ', 'WMS_ROLE_CREATE', 'WMS_ROLE_UPDATE',
                  'WMS_ROLE_DEACTIVATE', 'WMS_PERMISSION_CATALOG_READ',
                  'WMS_MEMBERSHIP_ROLE_READ',
                  'WMS_WAREHOUSE_READ', 'WMS_WAREHOUSE_CREATE', 'WMS_WAREHOUSE_UPDATE',
                  'WMS_LOCATION_READ', 'WMS_LOCATION_CREATE', 'WMS_LOCATION_UPDATE',
                  'WMS_ITEM_READ', 'WMS_ITEM_CREATE', 'WMS_ITEM_UPDATE',
                  'WMS_ITEM_CATEGORY_READ', 'WMS_ITEM_CATEGORY_MANAGE',
                  'WMS_UOM_CATALOG_READ', 'WMS_INVENTORY_READ', 'WMS_INVENTORY_LEDGER_READ'
                ) ORDER BY module, permission_code
                """);
    }

    @Transactional
    public List<String> replaceRolePermissions(
            WmsAuthenticatedPrincipal principal, String rolePublicId, List<String> requestedCodes,
            long expectedVersion) {
        Access access = access(principal, "WMS_ROLE_PERMISSION_MANAGE");
        lockTenant(access.tenantId());
        Map<String, Object> role = requireRole(access.tenantId(), rolePublicId);
        if (number(role, "is_system_role") != 0) {
            throw new AccessDeniedException("System role grants are protected");
        }
        if (number(role, "version") != expectedVersion) throw new IllegalStateException("Role version conflict");
        if (requestedCodes == null || requestedCodes.size() > 100
                || requestedCodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Permission set is invalid");
        }
        Set<String> requested = requestedCodes.stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.size() != requestedCodes.size()) throw new IllegalArgumentException("Duplicate permission code");
        if (!DELEGABLE_PERMISSIONS.containsAll(requested)
                || !access.permissions().containsAll(requested)) {
            throw new AccessDeniedException("Requested permissions are not delegable");
        }
        List<String> existing = jdbc.queryForList("""
                SELECT p.permission_code FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.role_id = ? ORDER BY p.permission_code
                """, String.class, number(role, "id"));
        List<String> missing = requested.stream().filter(code ->
                jdbc.queryForList("SELECT id FROM permissions WHERE permission_code = ?", Long.class, code).isEmpty())
                .toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Unknown permission code");
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", number(role, "id"));
        for (String code : requested.stream().sorted().toList()) {
            jdbc.update("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT ?, id FROM permissions WHERE permission_code = ?
                    """, number(role, "id"), code);
        }
        jdbc.update("UPDATE roles SET version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                number(role, "id"));
        audit(access, "PERMISSIONS", rolePublicId, Map.of("permissions", existing),
                Map.of("permissions", requested.stream().sorted().toList()));
        return requested.stream().sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<String> membershipRoles(WmsAuthenticatedPrincipal principal, String membershipPublicId) {
        return membershipRoles(principal, membershipPublicId, 0, 200);
    }

    @Transactional(readOnly = true)
    public List<String> membershipRoles(WmsAuthenticatedPrincipal principal, String membershipPublicId,
                                        int page, int size) {
        Access access = access(principal, "WMS_MEMBERSHIP_ROLE_READ");
        Long membershipId = activeMembership(access.tenantId(), membershipPublicId);
        long offset = boundedOffset(page, size);
        return jdbc.queryForList("""
                SELECT r.public_id FROM membership_roles mr
                JOIN roles r ON r.id = mr.role_id AND r.tenant_id = mr.tenant_id
                WHERE mr.tenant_id = ? AND mr.membership_id = ? AND r.status = 'ACTIVE'
                ORDER BY r.role_code LIMIT ? OFFSET ?
                """, String.class, access.tenantId(), membershipId, size, offset);
    }

    @Transactional
    public void assignRole(
            WmsAuthenticatedPrincipal principal, String membershipPublicId, String rolePublicId) {
        Access access = access(principal, "WMS_MEMBERSHIP_ROLE_MANAGE");
        lockTenant(access.tenantId());
        Long membershipId = activeMembership(access.tenantId(), membershipPublicId);
        Map<String, Object> role = requireRole(access.tenantId(), rolePublicId);
        if (!"ACTIVE".equals(role.get("status"))) throw new IllegalArgumentException("Role is not active");
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM membership_roles
                WHERE tenant_id = ? AND membership_id = ? AND role_id = ?
                """, Integer.class, access.tenantId(), membershipId, number(role, "id"));
        if (existing != null && existing > 0) return;
        jdbc.update("""
                INSERT INTO membership_roles (tenant_id, membership_id, role_id)
                VALUES (?, ?, ?)
                """, access.tenantId(), membershipId, number(role, "id"));
        audit(access, "ASSIGN", membershipPublicId, Map.of(), Map.of("rolePublicId", rolePublicId));
    }

    @Transactional
    public void removeRole(
            WmsAuthenticatedPrincipal principal, String membershipPublicId, String rolePublicId) {
        Access access = access(principal, "WMS_MEMBERSHIP_ROLE_MANAGE");
        lockTenant(access.tenantId());
        Long membershipId = activeMembership(access.tenantId(), membershipPublicId);
        Map<String, Object> role = requireRole(access.tenantId(), rolePublicId);
        Long roleId = number(role, "id");
        Integer assigned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM membership_roles
                WHERE tenant_id = ? AND membership_id = ? AND role_id = ?
                """, Integer.class, access.tenantId(), membershipId, roleId);
        if (assigned == null || assigned == 0) return;
        if ("TENANT_OWNER".equals(role.get("role_code"))) {
            Integer owners = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM membership_roles mr
                    JOIN tenant_memberships m ON m.id = mr.membership_id AND m.tenant_id = mr.tenant_id
                    JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
                    WHERE mr.tenant_id = ? AND mr.role_id = ? AND m.status = 'ACTIVE'
                    """, Integer.class, access.tenantId(), roleId);
            if (owners == null || owners <= 1) {
                throw new IllegalStateException("The last active tenant owner cannot be removed");
            }
        }
        int removed = jdbc.update("""
                DELETE FROM membership_roles
                WHERE tenant_id = ? AND membership_id = ? AND role_id = ?
                """, access.tenantId(), membershipId, roleId);
        if (removed == 1) audit(access, "REMOVE", membershipPublicId,
                Map.of("rolePublicId", rolePublicId), Map.of());
    }

    private Access access(WmsAuthenticatedPrincipal principal, String requiredPermission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())
                || !Objects.equals(resolved.userPublicId(), principal.userPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission(requiredPermission)) throw new AccessDeniedException("Permission denied");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId(),
                resolved.permissionCodes());
    }

    private void lockTenant(Long tenantId) {
        List<Long> rows = jdbc.queryForList("""
                SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, Long.class, tenantId);
        if (rows.isEmpty()) throw new AccessDeniedException("Tenant is unavailable");
    }

    private Long activeMembership(Long tenantId, String publicId) {
        List<Long> rows = jdbc.queryForList("""
                SELECT m.id FROM tenant_memberships m
                JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
                WHERE m.tenant_id = ? AND m.public_id = ? AND m.status = 'ACTIVE'
                """, Long.class, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Membership not found");
        return rows.getFirst();
    }

    private Map<String, Object> requireRole(Long tenantId, String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, public_id, role_code, role_name, description, is_system_role, status, version
                FROM roles WHERE tenant_id = ? AND public_id = ?
                """, tenantId, publicId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Role not found");
        return rows.getFirst();
    }

    private Map<String, Object> roleByPublicId(Long tenantId, String publicId) {
        return requireRole(tenantId, publicId);
    }

    private static Map<String, Object> roleResponse(Map<String, Object> role) {
        Map<String, Object> response = new LinkedHashMap<>(role);
        response.remove("id");
        return response;
    }

    private void audit(Access access, String action, String subjectPublicId,
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
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), AUDIT_TYPES.get(action),
                subjectPublicId, json, access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private static String normalizeCode(String code) {
        if (code == null) throw new IllegalArgumentException("Role code is required");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_CODE.matcher(normalized).matches()) throw new IllegalArgumentException("Role code is invalid");
        return normalized;
    }

    private static String bounded(String value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private record Access(Long tenantId, Long membershipId, String userPublicId, Set<String> permissions) {}
}

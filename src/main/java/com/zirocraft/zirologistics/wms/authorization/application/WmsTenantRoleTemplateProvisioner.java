package com.zirocraft.zirologistics.wms.authorization.application;

import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the immutable, server-managed operational role templates for a tenant.
 * Callers must invoke this inside the tenant creation transaction.
 */
public final class WmsTenantRoleTemplateProvisioner {
    private WmsTenantRoleTemplateProvisioner() {
    }

    public static void provision(JdbcTemplate jdbc, Long tenantId) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(tenantId, "tenantId");

        for (WmsTenantPermissionPolicy.RoleTemplate template
                : WmsTenantPermissionPolicy.systemRoleTemplates()) {
            jdbc.update("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                    VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE')
                    """,
                    UUID.randomUUID().toString(), tenantId, template.code(),
                    template.name(), template.description());

            Long roleId = jdbc.queryForObject("""
                    SELECT id FROM roles WHERE tenant_id = ? AND role_code = ?
                    """, Long.class, tenantId, template.code());
            if (roleId == null) {
                throw new IllegalStateException("Tenant system role could not be created");
            }

            for (String permissionCode : template.permissionCodes()) {
                List<Long> permissionIds = jdbc.queryForList("""
                        SELECT id FROM permissions WHERE permission_code = ?
                        """, Long.class, permissionCode);
                if (permissionIds.size() != 1) {
                    throw new IllegalStateException(
                            "Tenant system role references an unavailable permission");
                }
                jdbc.update("""
                        INSERT INTO role_permissions (role_id, permission_id)
                        VALUES (?, ?)
                        """, roleId, permissionIds.getFirst());
            }
        }
    }
}

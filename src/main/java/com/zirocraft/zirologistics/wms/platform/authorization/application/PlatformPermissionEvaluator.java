package com.zirocraft.zirologistics.wms.platform.authorization.application;

import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Read-only, deny-by-default platform permission evaluator.
 *
 * A platform JWT authenticates an operator but never carries authorization grants.
 * Every decision is derived from current persisted operator, role assignment,
 * role-permission, and permission state.
 */
@Service
public class PlatformPermissionEvaluator {
    private static final String HAS_PERMISSION_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM platform_operators operator
                  JOIN platform_operator_roles assignment
                    ON assignment.operator_id = operator.id
                  JOIN platform_roles role
                    ON role.id = assignment.role_id
                  JOIN platform_role_permissions role_permission
                    ON role_permission.role_id = role.id
                  JOIN platform_permissions permission
                    ON permission.id = role_permission.permission_id
                 WHERE operator.public_id = ?
                   AND operator.status = 'ACTIVE'
                   AND operator.email_verified_at IS NOT NULL
                   AND assignment.revoked_at IS NULL
                   AND (assignment.expires_at IS NULL OR assignment.expires_at > UTC_TIMESTAMP(6))
                   AND role.status = 'ACTIVE'
                   AND permission.permission_code = ?
                   AND permission.status = 'ACTIVE'
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public PlatformPermissionEvaluator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Returns true only for an exact active persisted grant. Invalid input and
     * authorization-store failures are denied; no wildcard or token claim is consulted.
     */
    public boolean hasPermission(PlatformAuthenticatedPrincipal principal, String permissionCode) {
        if (principal == null || principal.operatorPublicId() == null
                || principal.operatorPublicId().isBlank()
                || permissionCode == null || permissionCode.isBlank()
                || !permissionCode.equals(permissionCode.trim())) {
            return false;
        }

        try {
            Integer granted = jdbcTemplate.queryForObject(
                    HAS_PERMISSION_SQL, Integer.class, principal.operatorPublicId(), permissionCode);
            return Integer.valueOf(1).equals(granted);
        } catch (DataAccessException exception) {
            // Authorization must fail closed if the current grant state cannot be loaded.
            return false;
        }
    }
}

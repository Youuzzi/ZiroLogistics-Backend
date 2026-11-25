package com.zirocraft.zirologistics.wms.platform.authorization.application;

import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deny-by-default platform authorization backed by current persisted grants.
 * HIGH and CRITICAL permissions require MFA no older than five minutes at use time.
 */
@Service
public class PlatformPermissionEvaluator {
    private static final Duration HIGH_RISK_MFA_MAX_AGE = Duration.ofMinutes(5);
    private static final String PERMISSION_RISK_SQL = """
            SELECT CASE
                     WHEN COUNT(*) = 0 THEN NULL
                     WHEN SUM(permission.risk_level = 'CRITICAL') > 0 THEN 'CRITICAL'
                     WHEN SUM(permission.risk_level = 'HIGH') > 0 THEN 'HIGH'
                     WHEN SUM(permission.risk_level = 'MEDIUM') > 0 THEN 'MEDIUM'
                     ELSE 'LOW'
                   END
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
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PlatformPermissionEvaluator(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    PlatformPermissionEvaluator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns true only for an exact current persisted grant. Store failures,
     * missing grants, and stale/missing MFA for privileged permissions are denied.
     */
    public boolean hasPermission(PlatformAuthenticatedPrincipal principal, String permissionCode) {
        if (principal == null || principal.operatorPublicId() == null
                || principal.operatorPublicId().isBlank()
                || permissionCode == null || permissionCode.isBlank()
                || !permissionCode.equals(permissionCode.trim())) {
            return false;
        }

        try {
            String riskLevel = jdbcTemplate.queryForObject(
                    PERMISSION_RISK_SQL, String.class, principal.operatorPublicId(), permissionCode);
            if (riskLevel == null) return false;
            if ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) {
                Instant mfaAt = principal.mfaAuthenticatedAt();
                Instant now = clock.instant();
                return mfaAt != null && !mfaAt.isAfter(now)
                        && !mfaAt.isBefore(now.minus(HIGH_RISK_MFA_MAX_AGE));
            }
            return "LOW".equals(riskLevel) || "MEDIUM".equals(riskLevel);
        } catch (DataAccessException exception) {
            return false;
        }
    }
}

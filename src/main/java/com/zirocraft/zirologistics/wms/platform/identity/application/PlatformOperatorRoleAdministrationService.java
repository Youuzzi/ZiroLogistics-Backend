package com.zirocraft.zirologistics.wms.platform.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Least-privilege platform role assignment boundary.
 * Assigners may delegate only permissions they currently hold; PLATFORM_OWNER
 * is never assignable through routine administration.
 */
@Service
@Profile("wms")
public class PlatformOperatorRoleAdministrationService {
    private static final String ROLE_ASSIGN_PERMISSION = "PLATFORM_OPERATOR_ROLE_ASSIGN";
    private static final String DELEGATION_CEILING_SQL = """
            SELECT COUNT(*)
              FROM platform_role_permissions target_permission
              JOIN platform_permissions permission
                ON permission.id = target_permission.permission_id
             WHERE target_permission.role_id = ?
               AND permission.status = 'ACTIVE'
               AND NOT EXISTS (
                   SELECT 1
                     FROM platform_operator_roles actor_assignment
                     JOIN platform_roles actor_role
                       ON actor_role.id = actor_assignment.role_id
                     JOIN platform_role_permissions actor_permission
                       ON actor_permission.role_id = actor_role.id
                    WHERE actor_assignment.operator_id = ?
                      AND actor_assignment.revoked_at IS NULL
                      AND (actor_assignment.expires_at IS NULL
                           OR actor_assignment.expires_at > UTC_TIMESTAMP(6))
                      AND actor_role.status = 'ACTIVE'
                      AND actor_permission.permission_id = permission.id
               )
            """;

    private final PlatformPermissionEvaluator permissionEvaluator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PlatformOperatorRoleAdministrationService(
            PlatformPermissionEvaluator permissionEvaluator, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public void assignRole(String targetOperatorPublicId, String roleCode,
                           String reason, String operationRef, LocalDateTime expiresAtUtc) {
        String targetId = canonicalUuid(targetOperatorPublicId, "target operator");
        String operation = canonicalUuid(operationRef, "operation reference");
        String normalizedReason = normalizeReason(reason);
        String normalizedRole = normalizeRoleCode(roleCode);
        PlatformAuthenticatedPrincipal actor = authenticatedActor();
        requireRoleAssignmentPermission(actor);

        if (actor.operatorPublicId().equals(targetId) || "PLATFORM_OWNER".equals(normalizedRole)) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }

        Long actorId = activeVerifiedOperatorId(actor.operatorPublicId());
        Long targetIdValue = activeVerifiedOperatorId(targetId);
        Long roleId = jdbc.queryForObject("""
                SELECT id FROM platform_roles WHERE role_code = ? AND status = 'ACTIVE' FOR UPDATE
                """, Long.class, normalizedRole);

        Integer exceedsDelegation = jdbc.queryForObject(DELEGATION_CEILING_SQL,
                Integer.class, roleId, actorId);
        if (exceedsDelegation == null || exceedsDelegation != 0) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }
        if (expiresAtUtc != null) {
            Integer expiryIsFuture = jdbc.queryForObject(
                    "SELECT CASE WHEN ? > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END",
                    Integer.class, expiresAtUtc);
            if (!Integer.valueOf(1).equals(expiryIsFuture)) {
                throw new PlatformOperatorRoleAdministrationRejectedException();
            }
        }

        Integer alreadyAssigned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_operator_roles
                 WHERE operator_id = ? AND role_id = ? AND revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))
                """, Integer.class, targetIdValue, roleId);
        if (alreadyAssigned != null && alreadyAssigned > 0) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }

        jdbc.update("""
                INSERT INTO platform_operator_roles
                  (operator_id, role_id, assigned_by_operator_id, assigned_at, expires_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), ?)
                """, targetIdValue, roleId, actorId, expiresAtUtc);

        audit(actorId, operation, "PLATFORM_OPERATOR_ROLE_ASSIGNED", targetId,
                normalizedReason, Map.of("roleCode", normalizedRole,
                        "expiresAtUtc", expiresAtUtc == null ? "NONE" : expiresAtUtc.toString()));
    }

    @Transactional
    public void revokeRole(String targetOperatorPublicId, String roleCode,
                           String reason, String operationRef) {
        String targetId = canonicalUuid(targetOperatorPublicId, "target operator");
        String operation = canonicalUuid(operationRef, "operation reference");
        String normalizedReason = normalizeReason(reason);
        String normalizedRole = normalizeRoleCode(roleCode);
        PlatformAuthenticatedPrincipal actor = authenticatedActor();
        requireRoleAssignmentPermission(actor);

        if (actor.operatorPublicId().equals(targetId) || "PLATFORM_OWNER".equals(normalizedRole)) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }

        Long actorId = activeVerifiedOperatorId(actor.operatorPublicId());
        Long targetIdValue = activeVerifiedOperatorId(targetId);
        Long roleId = jdbc.queryForObject("""
                SELECT id FROM platform_roles WHERE role_code = ? AND status = 'ACTIVE' FOR UPDATE
                """, Long.class, normalizedRole);
        Long assignmentId = jdbc.queryForObject("""
                SELECT id FROM platform_operator_roles
                 WHERE operator_id = ? AND role_id = ? AND revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))
                 ORDER BY assigned_at DESC LIMIT 1 FOR UPDATE
                """, Long.class, targetIdValue, roleId);

        int revoked = jdbc.update("""
                UPDATE platform_operator_roles SET revoked_at = UTC_TIMESTAMP(6)
                 WHERE id = ? AND revoked_at IS NULL
                """, assignmentId);
        if (revoked != 1) throw new PlatformOperatorRoleAdministrationRejectedException();

        audit(actorId, operation, "PLATFORM_OPERATOR_ROLE_REVOKED", targetId,
                normalizedReason, Map.of("roleCode", normalizedRole));
    }

    private void requireRoleAssignmentPermission(PlatformAuthenticatedPrincipal actor) {
        if (!permissionEvaluator.hasPermission(actor, ROLE_ASSIGN_PERMISSION)) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }
    }

    private Long activeVerifiedOperatorId(String publicId) {
        return jdbc.queryForObject("""
                SELECT id FROM platform_operators
                 WHERE public_id = ? AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                 FOR UPDATE
                """, Long.class, publicId);
    }

    private void audit(Long actorId, String operationRef, String eventType,
                       String subjectPublicId, String reason, Map<String, Object> payload) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO platform_audit_events
                      (public_id, actor_operator_id, operation_ref, event_type,
                       subject_type, subject_public_id, reason, change_payload)
                    VALUES (?, ?, ?, ?, 'PLATFORM_OPERATOR', ?, ?, ?)
                    """, UUID.randomUUID().toString(), actorId, operationRef, eventType,
                    subjectPublicId, reason, objectMapper.writeValueAsString(payload));
            if (inserted != 1) throw new PlatformOperatorRoleAdministrationRejectedException();
        } catch (JsonProcessingException failure) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }
    }

    private static PlatformAuthenticatedPrincipal authenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PlatformAuthenticatedPrincipal principal)) {
            throw new PlatformOperatorRoleAdministrationRejectedException();
        }
        return principal;
    }

    private static String canonicalUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Platform " + field + " identifier is invalid");
        }
    }

    private static String normalizeRoleCode(String value) {
        if (value == null || !value.matches("PLATFORM_[A-Z_]{2,76}")) {
            throw new IllegalArgumentException("Platform role code is invalid");
        }
        return value;
    }

    private static String normalizeReason(String value) {
        if (value == null || value.trim().length() < 10 || value.trim().length() > 500) {
            throw new IllegalArgumentException("Platform role administration reason is invalid");
        }
        return value.trim();
    }
}

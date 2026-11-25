package com.zirocraft.zirologistics.wms.platform.support.application;

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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("wms")
public class PlatformSupportGrantService {
    private static final Duration MAX_GRANT_WINDOW = Duration.ofHours(4);
    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "TENANT_METADATA_READ",
            "TENANT_CONFIGURATION_READ",
            "INTEGRATION_DIAGNOSTICS_READ",
            "TENANT_AUDIT_METADATA_READ");

    private final PlatformPermissionEvaluator permissions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformSupportGrantService(
            PlatformPermissionEvaluator permissions, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(permissions, jdbc, objectMapper, Clock.systemUTC());
    }

    PlatformSupportGrantService(
            PlatformPermissionEvaluator permissions, JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.permissions = Objects.requireNonNull(permissions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public String requestGrant(PlatformSupportGrantRequest request) {
        Objects.requireNonNull(request, "support grant request is required");
        PlatformAuthenticatedPrincipal actor = authenticatedActor();
        requirePermission(actor, "PLATFORM_SUPPORT_GRANT_CREATE");

        String tenantPublicId = canonicalUuid(request.tenantPublicId(), "tenant public ID");
        String operationRef = canonicalUuid(request.operationRef(), "operation reference");
        String purpose = normalizeText(request.purpose(), 20, 500, "purpose");
        Instant startsAt = Objects.requireNonNull(request.startsAt(), "support grant start is required");
        Instant expiresAt = Objects.requireNonNull(request.expiresAt(), "support grant expiry is required");
        Instant now = clock.instant();
        if (startsAt.isBefore(now) || !expiresAt.isAfter(startsAt)
                || expiresAt.isAfter(now.plus(MAX_GRANT_WINDOW))) {
            throw new PlatformSupportGrantRejectedException();
        }
        List<String> scopes = normalizeScopes(request.scopes());

        Long actorId = activeVerifiedOperatorId(actor.operatorPublicId());
        Long tenantId = jdbc.queryForObject("""
                SELECT id FROM tenants WHERE public_id = ? AND status = 'ACTIVE' FOR UPDATE
                """, Long.class, tenantPublicId);
        String grantPublicId = UUID.randomUUID().toString();

        int inserted = jdbc.update("""
                INSERT INTO platform_support_grants
                  (public_id, tenant_id, requested_by_operator_id, operation_ref, purpose,
                   status, starts_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?)
                """, grantPublicId, tenantId, actorId, operationRef, purpose,
                Timestamp.from(startsAt), Timestamp.from(expiresAt));
        if (inserted != 1) throw new PlatformSupportGrantRejectedException();

        Long grantId = jdbc.queryForObject(
                "SELECT id FROM platform_support_grants WHERE public_id = ?", Long.class, grantPublicId);
        for (String scope : scopes) {
            jdbc.update("""
                    INSERT INTO platform_support_grant_scopes (grant_id, scope_code)
                    VALUES (?, ?)
                    """, grantId, scope);
        }
        audit(actorId, operationRef, "SUPPORT_GRANT_REQUESTED", grantPublicId, purpose,
                Map.of("tenantPublicId", tenantPublicId, "scopes", scopes,
                        "startsAt", startsAt.toString(), "expiresAt", expiresAt.toString()));
        return grantPublicId;
    }

    @Transactional
    public void approveGrant(String grantPublicId, String reason, String operationRef) {
        decideGrant(grantPublicId, reason, operationRef, true);
    }

    @Transactional
    public void rejectGrant(String grantPublicId, String reason, String operationRef) {
        decideGrant(grantPublicId, reason, operationRef, false);
    }

    @Transactional
    public void revokeGrant(String grantPublicId, String reason, String operationRef) {
        PlatformAuthenticatedPrincipal actor = authenticatedActor();
        requirePermission(actor, "PLATFORM_SUPPORT_GRANT_REVOKE");
        String grantId = canonicalUuid(grantPublicId, "support grant ID");
        String operation = canonicalUuid(operationRef, "operation reference");
        String normalizedReason = normalizeText(reason, 10, 500, "reason");
        Long actorId = activeVerifiedOperatorId(actor.operatorPublicId());
        Map<String, Object> grant = lockGrant(grantId);
        if (!"APPROVED".equals(grant.get("status"))) throw new PlatformSupportGrantRejectedException();

        int updated = jdbc.update("""
                UPDATE platform_support_grants
                   SET status = 'REVOKED', revoked_by_operator_id = ?,
                       revoked_at = UTC_TIMESTAMP(6), version = version + 1
                 WHERE id = ? AND status = 'APPROVED' AND revoked_at IS NULL
                """, actorId, grant.get("id"));
        if (updated != 1) throw new PlatformSupportGrantRejectedException();
        audit(actorId, operation, "SUPPORT_GRANT_REVOKED", grantId, normalizedReason,
                Map.of("tenantId", grant.get("tenant_id")));
    }

    private void decideGrant(String grantPublicId, String reason, String operationRef, boolean approve) {
        PlatformAuthenticatedPrincipal actor = authenticatedActor();
        requirePermission(actor, "PLATFORM_SUPPORT_GRANT_APPROVE");
        String grantId = canonicalUuid(grantPublicId, "support grant ID");
        String operation = canonicalUuid(operationRef, "operation reference");
        String normalizedReason = normalizeText(reason, 10, 500, "decision reason");
        Long actorId = activeVerifiedOperatorId(actor.operatorPublicId());
        Map<String, Object> grant = lockGrant(grantId);
        Long requesterId = ((Number) grant.get("requested_by_operator_id")).longValue();
        if (!"REQUESTED".equals(grant.get("status")) || requesterId.equals(actorId)) {
            throw new PlatformSupportGrantRejectedException();
        }

        int updated;
        String eventType;
        if (approve) {
            updated = jdbc.update("""
                    UPDATE platform_support_grants
                       SET status = 'APPROVED', approved_by_operator_id = ?,
                           approved_at = UTC_TIMESTAMP(6), version = version + 1
                     WHERE id = ? AND status = 'REQUESTED' AND expires_at > UTC_TIMESTAMP(6)
                    """, actorId, grant.get("id"));
            eventType = "SUPPORT_GRANT_APPROVED";
        } else {
            updated = jdbc.update("""
                    UPDATE platform_support_grants
                       SET status = 'REJECTED', rejected_by_operator_id = ?,
                           rejected_at = UTC_TIMESTAMP(6), version = version + 1
                     WHERE id = ? AND status = 'REQUESTED'
                    """, actorId, grant.get("id"));
            eventType = "SUPPORT_GRANT_REJECTED";
        }
        if (updated != 1) throw new PlatformSupportGrantRejectedException();
        audit(actorId, operation, eventType, grantId, normalizedReason,
                Map.of("tenantId", grant.get("tenant_id")));
    }

    private Map<String, Object> lockGrant(String grantPublicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, tenant_id, requested_by_operator_id, status
                  FROM platform_support_grants
                 WHERE public_id = ?
                 FOR UPDATE
                """, grantPublicId);
        if (rows.isEmpty()) throw new PlatformSupportGrantRejectedException();
        return rows.getFirst();
    }

    private Long activeVerifiedOperatorId(String publicId) {
        return jdbc.queryForObject("""
                SELECT id FROM platform_operators
                 WHERE public_id = ? AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                 FOR UPDATE
                """, Long.class, publicId);
    }

    private void requirePermission(PlatformAuthenticatedPrincipal actor, String permission) {
        if (!permissions.hasPermission(actor, permission)) {
            throw new PlatformSupportGrantRejectedException();
        }
    }

    private void audit(Long actorId, String operationRef, String eventType,
                       String grantPublicId, String reason, Map<String, Object> payload) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO platform_audit_events
                      (public_id, actor_operator_id, operation_ref, event_type,
                       subject_type, subject_public_id, reason, change_payload)
                    VALUES (?, ?, ?, ?, 'SUPPORT_GRANT', ?, ?, ?)
                    """, UUID.randomUUID().toString(), actorId, operationRef, eventType,
                    grantPublicId, reason, objectMapper.writeValueAsString(payload));
            if (inserted != 1) throw new PlatformSupportGrantRejectedException();
        } catch (JsonProcessingException failure) {
            throw new PlatformSupportGrantRejectedException();
        }
    }

    private static PlatformAuthenticatedPrincipal authenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PlatformAuthenticatedPrincipal principal)) {
            throw new PlatformSupportGrantRejectedException();
        }
        return principal;
    }

    private static List<String> normalizeScopes(Set<PlatformSupportScope> scopes) {
        if (scopes == null || scopes.isEmpty()) throw new PlatformSupportGrantRejectedException();
        List<String> values = scopes.stream().map(Enum::name).sorted(Comparator.naturalOrder()).toList();
        if (!ALLOWED_SCOPES.containsAll(values)) throw new PlatformSupportGrantRejectedException();
        return values;
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

    private static String normalizeText(String value, int minimum, int maximum, String field) {
        if (value == null || value.trim().length() < minimum || value.trim().length() > maximum) {
            throw new IllegalArgumentException("Platform support grant " + field + " is invalid");
        }
        return value.trim();
    }
}

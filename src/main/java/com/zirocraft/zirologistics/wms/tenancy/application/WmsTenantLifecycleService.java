package com.zirocraft.zirologistics.wms.tenancy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("wms")
public class WmsTenantLifecycleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WmsTenantLifecycleService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public WmsTenantLifecycleResult transition(
            WmsTenantLifecycleAction action,
            String tenantPublicId,
            String reason,
            String operationRef,
            String confirmation) {
        Objects.requireNonNull(action, "tenant lifecycle action is required");
        String normalizedTenantId = canonicalUuid(tenantPublicId, "tenant public ID");
        String normalizedOperationRef = canonicalUuid(operationRef, "operation reference");
        String normalizedReason = normalizeReason(reason);

        List<Map<String, Object>> tenantRows = jdbc.queryForList("""
                SELECT id, tenant_code, status
                FROM tenants
                WHERE public_id = ?
                FOR UPDATE
                """, normalizedTenantId);
        if (tenantRows.isEmpty()) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle operation was rejected");
        }
        Map<String, Object> tenant = tenantRows.getFirst();
        Long tenantId = ((Number) tenant.get("id")).longValue();
        String tenantCode = (String) tenant.get("tenant_code");
        String currentStatus = (String) tenant.get("status");

        List<Map<String, Object>> previousOperation = jdbc.queryForList("""
                SELECT tenant_id, event_type
                FROM tenant_audit_events
                WHERE operation_ref = ?
                """, normalizedOperationRef);
        if (!previousOperation.isEmpty()) {
            Map<String, Object> recorded = previousOperation.getFirst();
            if (((Number) recorded.get("tenant_id")).longValue() == tenantId
                    && Objects.equals(recorded.get("event_type"), eventType(action))
                    && Objects.equals(currentStatus, targetStatus(action))) {
                return new WmsTenantLifecycleResult(normalizedOperationRef, normalizedTenantId,
                        tenantCode, sourceStatus(action), currentStatus, true);
            }
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle operation was rejected");
        }

        if (action == WmsTenantLifecycleAction.CLOSE
                && !Objects.equals(confirmation, "CLOSE:" + tenantCode)) {
            throw new WmsTenantLifecycleRejectedException("Tenant closure confirmation is invalid");
        }
        if (!Objects.equals(currentStatus, sourceStatus(action))) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle transition is not allowed");
        }

        String targetStatus = targetStatus(action);
        int updated = jdbc.update("""
                UPDATE tenants
                SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND status = ?
                """, targetStatus, tenantId, currentStatus);
        if (updated != 1) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle transition is not allowed");
        }

        String payload = serialize(Map.of(
                "from", currentStatus,
                "to", targetStatus,
                "reason", normalizedReason,
                "operationRef", normalizedOperationRef));
        int audited = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                VALUES (?, ?, NULL, 'PLATFORM_OPERATOR', ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), tenantId, normalizedOperationRef,
                eventType(action), normalizedTenantId, payload);
        if (audited != 1) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle operation was rejected");
        }

        return new WmsTenantLifecycleResult(normalizedOperationRef, normalizedTenantId,
                tenantCode, currentStatus, targetStatus, false);
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle operation was rejected");
        }
    }

    private static String canonicalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle " + field + " is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            return parsed.toString();
        } catch (RuntimeException invalid) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle " + field + " is invalid");
        }
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle reason is required");
        }
        String reason = value.trim();
        if (reason.length() < 10 || reason.length() > 500) {
            throw new WmsTenantLifecycleRejectedException("Tenant lifecycle reason length is invalid");
        }
        return reason;
    }

    private static String sourceStatus(WmsTenantLifecycleAction action) {
        return action == WmsTenantLifecycleAction.SUSPEND ? "ACTIVE" : "SUSPENDED";
    }

    private static String targetStatus(WmsTenantLifecycleAction action) {
        return switch (action) {
            case SUSPEND -> "SUSPENDED";
            case REACTIVATE -> "ACTIVE";
            case CLOSE -> "CLOSED";
        };
    }

    private static String eventType(WmsTenantLifecycleAction action) {
        return switch (action) {
            case SUSPEND -> "TENANT_SUSPENDED";
            case REACTIVATE -> "TENANT_REACTIVATED";
            case CLOSE -> "TENANT_CLOSED";
        };
    }
}

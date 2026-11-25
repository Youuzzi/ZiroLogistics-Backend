package com.zirocraft.zirologistics.wms.platform.tenancy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleAction;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated control-plane application boundary for tenant lifecycle changes.
 * The caller cannot supply an actor ID: identity is resolved from the current
 * Spring Security context and current persisted permissions are checked per action.
 */
@Service
@Profile("wms")
public class PlatformTenantLifecycleApplicationService {
    private final PlatformPermissionEvaluator permissionEvaluator;
    private final WmsTenantLifecycleService lifecycleService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PlatformTenantLifecycleApplicationService(
            PlatformPermissionEvaluator permissionEvaluator,
            WmsTenantLifecycleService lifecycleService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional
    public WmsTenantLifecycleResult transition(PlatformTenantLifecycleRequest request) {
        Objects.requireNonNull(request, "tenant lifecycle request is required");
        String tenantPublicId = canonicalUuid(request.tenantPublicId(), "tenant public ID");
        String operationRef = canonicalUuid(request.operationRef(), "operation reference");
        String reason = normalizeReason(request.reason());
        Objects.requireNonNull(request.action(), "tenant lifecycle action is required");

        PlatformAuthenticatedPrincipal principal = authenticatedPrincipal();
        String permission = permissionFor(request.action());
        if (!permissionEvaluator.hasPermission(principal, permission)) {
            throw new PlatformTenantLifecycleAccessDeniedException();
        }

        WmsTenantLifecycleResult result = lifecycleService.transition(
                request.action(), tenantPublicId, reason, operationRef,
                principal.operatorPublicId(), request.confirmation());

        if (!result.alreadyApplied()) {
            Long actorId = jdbc.queryForObject(
                    "SELECT id FROM platform_operators WHERE public_id = ? AND status = 'ACTIVE' AND email_verified_at IS NOT NULL FOR UPDATE",
                    Long.class, principal.operatorPublicId());
            String payload = serialize(Map.of(
                    "action", request.action().name(),
                    "previousStatus", result.previousStatus(),
                    "currentStatus", result.currentStatus()));
            int inserted = jdbc.update("""
                    INSERT INTO platform_audit_events
                      (public_id, actor_operator_id, operation_ref, event_type,
                       subject_type, subject_public_id, reason, change_payload)
                    VALUES (?, ?, ?, ?, 'TENANT', ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), actorId, operationRef,
                    "TENANT_" + request.action().name(), tenantPublicId, reason, payload);
            if (inserted != 1) {
                throw new PlatformTenantLifecycleAccessDeniedException();
            }
        }
        return result;
    }

    private static PlatformAuthenticatedPrincipal authenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PlatformAuthenticatedPrincipal principal)) {
            throw new PlatformTenantLifecycleAccessDeniedException();
        }
        return principal;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new PlatformTenantLifecycleAccessDeniedException();
        }
    }

    private static String permissionFor(WmsTenantLifecycleAction action) {
        return switch (action) {
            case SUSPEND -> "PLATFORM_TENANT_SUSPEND";
            case REACTIVATE -> "PLATFORM_TENANT_REACTIVATE";
            case CLOSE -> "PLATFORM_TENANT_CLOSE";
        };
    }

    private static String canonicalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tenant lifecycle " + field + " is required");
        }
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Tenant lifecycle " + field + " is invalid");
        }
        return parsed.toString();
    }

    private static String normalizeReason(String value) {
        if (value == null || value.trim().length() < 10 || value.trim().length() > 500) {
            throw new IllegalArgumentException("Tenant lifecycle reason length is invalid");
        }
        return value.trim();
    }
}

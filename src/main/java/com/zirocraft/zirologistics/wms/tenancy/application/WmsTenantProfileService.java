package com.zirocraft.zirologistics.wms.tenancy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.domain.WmsTenantLocalePolicy;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("wms")
public class WmsTenantProfileService {
    public static final String UPDATE_PERMISSION = "WMS_TENANT_PROFILE_UPDATE";

    private final WmsMembershipAccessService membershipAccessService;
    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WmsTenantProfileService(
            WmsMembershipAccessService membershipAccessService,
            TenantRepository tenantRepository,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.membershipAccessService = Objects.requireNonNull(membershipAccessService);
        this.tenantRepository = Objects.requireNonNull(tenantRepository);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Transactional(readOnly = true)
    public WmsTenantProfile getCurrent(WmsAuthenticatedPrincipal principal) {
        WmsMembershipAccess access = resolveAccess(principal);
        TenantEntity tenant = requireTenant(access);
        return toProfile(tenant);
    }

    @Transactional
    public WmsTenantProfile updateCurrent(
            WmsAuthenticatedPrincipal principal,
            WmsTenantProfileUpdate update) {
        Objects.requireNonNull(update, "tenant profile update must not be null");
        WmsMembershipAccess access = resolveAccess(principal);
        if (!access.hasPermission(UPDATE_PERMISSION)) {
            throw new AccessDeniedException("Missing tenant profile update permission");
        }
        if (update.expectedVersion() == null || update.expectedVersion() < 0) {
            throw new IllegalArgumentException("Expected tenant profile version is required");
        }

        TenantEntity tenant = requireTenant(access);
        if (!Objects.equals(tenant.getVersion(), update.expectedVersion())) {
            throw new WmsTenantProfileConflictException();
        }

        String legalName = bounded(update.legalName(), "legal name", 2, 200);
        String displayName = bounded(update.displayName(), "display name", 2, 150);
        String timezone = validateTimezone(update.timezone());
        String currency = validateCurrency(update.defaultCurrency());

        Map<String, Object> before = profileFields(tenant);
        boolean changed = !Objects.equals(tenant.getLegalName(), legalName)
                || !Objects.equals(tenant.getDisplayName(), displayName)
                || !Objects.equals(tenant.getTimezone(), timezone)
                || !Objects.equals(tenant.getDefaultCurrency(), currency);
        if (!changed) {
            return toProfile(tenant);
        }

        tenant.setLegalName(legalName);
        tenant.setDisplayName(displayName);
        tenant.setTimezone(timezone);
        tenant.setDefaultCurrency(currency);

        try {
            TenantEntity saved = tenantRepository.saveAndFlush(tenant);
            writeAudit(access, saved, before, profileFields(saved));
            return toProfile(saved);
        } catch (OptimisticLockingFailureException concurrentUpdate) {
            throw new WmsTenantProfileConflictException();
        }
    }

    private WmsMembershipAccess resolveAccess(WmsAuthenticatedPrincipal principal) {
        if (principal == null || principal.userPublicId() == null || principal.userPublicId().isBlank()
                || principal.membershipPublicId() == null || principal.membershipPublicId().isBlank()) {
            throw new AccessDeniedException("Resolved tenant context is required");
        }
        WmsMembershipAccess access = membershipAccessService.resolve(
                principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(principal.tenantId(), access.tenantId())
                || !Objects.equals(principal.tenantPublicId(), access.tenantPublicId())
                || !Objects.equals(principal.membershipId(), access.membershipId())
                || !Objects.equals(principal.membershipPublicId(), access.membershipPublicId())) {
            throw new WmsMembershipAccessDeniedException();
        }
        return access;
    }

    private TenantEntity requireTenant(WmsMembershipAccess access) {
        TenantEntity tenant = tenantRepository.findById(access.tenantId())
                .orElseThrow(() -> new AccessDeniedException("Resolved tenant context is unavailable"));
        if (!tenant.getPublicId().equals(access.tenantPublicId())
                || tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new AccessDeniedException("Resolved tenant context is unavailable");
        }
        return tenant;
    }

    private void writeAudit(
            WmsMembershipAccess access,
            TenantEntity tenant,
            Map<String, Object> before,
            Map<String, Object> after) {
        Map<String, Object> change_payload = Map.of("before", before, "after", after);
        final String serialized;
        try {
            serialized = objectMapper.writeValueAsString(change_payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Tenant audit event could not be serialized");
        }

        int inserted = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, actor_reference, operation_ref, event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', NULL, ?, 'TENANT_PROFILE_UPDATED', t.public_id, ?
                FROM tenants t
                INNER JOIN tenant_memberships m
                  ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                INNER JOIN users u
                  ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.public_id = ? AND t.status = 'ACTIVE'
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), serialized,
                access.membershipId(), access.userPublicId(),
                tenant.getId(), tenant.getPublicId());
        if (inserted != 1) {
            throw new AccessDeniedException("Tenant audit actor is no longer active");
        }
    }

    private static Map<String, Object> profileFields(TenantEntity tenant) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("legalName", tenant.getLegalName());
        fields.put("displayName", tenant.getDisplayName());
        fields.put("timezone", tenant.getTimezone());
        fields.put("defaultCurrency", tenant.getDefaultCurrency());
        return fields;
    }

    private static WmsTenantProfile toProfile(TenantEntity tenant) {
        return new WmsTenantProfile(
                tenant.getPublicId(), tenant.getTenantCode(), tenant.getLegalName(),
                tenant.getDisplayName(), tenant.getStatus(), tenant.getTimezone(),
                tenant.getDefaultCurrency(), tenant.getVersion());
    }

    private static String bounded(String value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static String validateTimezone(String value) {
        return WmsTenantLocalePolicy.normalizeTimezone(bounded(value, "timezone", 1, 60));
    }

    private static String validateCurrency(String value) {
        return WmsTenantLocalePolicy.normalizeCurrency(bounded(value, "default currency", 3, 3));
    }
}

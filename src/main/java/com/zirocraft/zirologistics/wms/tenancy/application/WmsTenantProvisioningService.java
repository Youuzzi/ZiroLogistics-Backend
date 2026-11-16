package com.zirocraft.zirologistics.wms.tenancy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailNormalizer;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.config.WmsTenantProvisioningProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("wms")
public class WmsTenantProvisioningService {
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,63}");
    private static final List<String> OWNER_PERMISSIONS = List.of(
            "WMS_IDENTITY_MEMBERSHIP_INVITE", "WMS_TENANT_PROFILE_UPDATE");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public WmsTenantProvisioningService(
            JdbcTemplate jdbc,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public WmsTenantProvisioningResult provision(WmsTenantProvisioningProperties properties) {
        Input input = validate(properties);
        // Argon2id work is performed before opening the provisioning transaction.
        String unreachablePassword = UUID.randomUUID() + UUID.randomUUID().toString();
        String passwordHash = passwordEncoder.encode(unreachablePassword);
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new WmsTenantProvisioningRejectedException("Tenant owner credential initialization failed");
        }
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> provisionInTransaction(input, passwordHash)),
                    "Tenant provisioning transaction returned no result");
        } catch (DataIntegrityViolationException conflict) {
            throw new WmsTenantProvisioningRejectedException(
                    "Tenant provisioning conflicted with an existing identity or operation");
        }
    }

    private WmsTenantProvisioningResult provisionInTransaction(Input input, String passwordHash) {
        WmsTenantProvisioningResult previous = findPrevious(
                input.operationRef(), input);
        if (previous != null) return previous;

        String tenantPublicId = UUID.randomUUID().toString();
        String membershipPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();

        jdbc.update("""
                INSERT INTO tenants
                  (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, tenantPublicId, input.tenantCode(), input.legalName(), input.displayName(),
                input.timezone(), input.currency());
        Long tenantId = jdbc.queryForObject(
                "SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        OwnerIdentity owner = findOrCreateOwner(input, passwordHash);
        Integer existingMembership = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id = ? AND user_id = ?
                """, Integer.class, tenantId, owner.userId());
        if (existingMembership != null && existingMembership != 0) {
            throw new WmsTenantProvisioningRejectedException("Tenant owner membership already exists");
        }

        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', CASE WHEN ? = 'ACTIVE' THEN UTC_TIMESTAMP(6) ELSE NULL END)
                """, membershipPublicId, tenantId, owner.userId(), owner.status().name());

        jdbc.update("""
                INSERT INTO roles
                  (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, 'TENANT_OWNER', 'Tenant Owner',
                        'Tenant owner role created by controlled tenant provisioning', 1, 'ACTIVE')
                """, rolePublicId, tenantId);
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        Long membershipId = jdbc.queryForObject(
                "SELECT id FROM tenant_memberships WHERE public_id = ?", Long.class, membershipPublicId);

        jdbc.update("""
                INSERT INTO membership_roles (tenant_id, membership_id, role_id)
                VALUES (?, ?, ?)
                """, tenantId, membershipId, roleId);
        for (String permissionCode : OWNER_PERMISSIONS) {
            Long permissionId = jdbc.queryForObject(
                    "SELECT id FROM permissions WHERE permission_code = ?", Long.class, permissionCode);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                    roleId, permissionId);
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("tenantCode", input.tenantCode());
        auditPayload.put("ownerUserPublicId", owner.publicId());
        auditPayload.put("ownerRolePublicId", rolePublicId);
        auditPayload.put("ownerVerificationRequired", owner.status() == UserStatus.PENDING_VERIFICATION);
        auditPayload.put("reason", input.reason());
        String serialized = serialize(auditPayload);

        int audited = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, actor_reference, operation_ref,
                   event_type, subject_public_id, change_payload)
                VALUES (?, ?, NULL, 'PLATFORM_OPERATOR', ?, ?, 'TENANT_PROVISIONED', ?, ?)
                """, UUID.randomUUID().toString(), tenantId, input.operatorReference(),
                input.operationRef(), tenantPublicId, serialized);
        if (audited != 1) {
            throw new WmsTenantProvisioningRejectedException("Tenant provisioning could not be audited");
        }

        return new WmsTenantProvisioningResult(
                input.operationRef(), tenantPublicId, input.tenantCode(), owner.publicId(),
                membershipPublicId, rolePublicId,
                owner.status() == UserStatus.PENDING_VERIFICATION, false);
    }

    private OwnerIdentity findOrCreateOwner(Input input, String passwordHash) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, public_id, status, email_verified_at
                FROM users
                WHERE email = ?
                FOR UPDATE
                """, input.ownerEmail());
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.getFirst();
            UserStatus status = UserStatus.valueOf((String) row.get("status"));
            if (status != UserStatus.ACTIVE || row.get("email_verified_at") == null) {
                throw new WmsTenantProvisioningRejectedException(
                        "Existing tenant owner identity is not active and email-verified");
            }
            return new OwnerIdentity(
                    ((Number) row.get("id")).longValue(), (String) row.get("public_id"), status);
        }

        String userPublicId = UUID.randomUUID().toString();
        // A high-entropy random secret is hashed and discarded; the owner sets a real password
        // after email verification through the existing password-reset lifecycle.
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status, email_verified_at)
                VALUES (?, ?, ?, ?, 'PENDING_VERIFICATION', NULL)
                """, userPublicId, input.ownerEmail(), passwordHash, input.ownerFullName());
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);
        return new OwnerIdentity(userId, userPublicId, UserStatus.PENDING_VERIFICATION);
    }

    private WmsTenantProvisioningResult findPrevious(String operationRef, Input input) {
        List<Map<String, Object>> operation = jdbc.queryForList("""
                SELECT event_type, subject_public_id
                FROM tenant_audit_events
                WHERE operation_ref = ?
                """, operationRef);
        if (operation.isEmpty()) return null;
        Map<String, Object> event = operation.getFirst();
        if (!"TENANT_PROVISIONED".equals(event.get("event_type"))) {
            throw new WmsTenantProvisioningRejectedException("Tenant provisioning operation reference was already used");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.public_id AS tenant_public_id, t.tenant_code, t.legal_name, t.display_name,
                       t.timezone, t.default_currency, a.actor_reference AS operator_reference,
                       JSON_UNQUOTE(JSON_EXTRACT(a.change_payload, '$.reason')) AS reason,
                       u.public_id AS owner_user_public_id, u.email AS owner_email,
                       u.full_name AS owner_full_name, u.status AS owner_status,
                       m.public_id AS membership_public_id, r.public_id AS role_public_id
                FROM tenant_audit_events a
                JOIN tenants t ON t.public_id = a.subject_public_id
                JOIN roles r ON r.tenant_id = t.id AND r.role_code = 'TENANT_OWNER'
                JOIN membership_roles mr ON mr.tenant_id = t.id AND mr.role_id = r.id
                JOIN tenant_memberships m ON m.id = mr.membership_id AND m.tenant_id = t.id
                JOIN users u ON u.id = m.user_id
                WHERE t.public_id = ?
                """, event.get("subject_public_id"));
        if (rows.isEmpty()) {
            throw new WmsTenantProvisioningRejectedException("Tenant provisioning operation reference is inconsistent");
        }
        Map<String, Object> row = rows.getFirst();
        if (!input.tenantCode().equals(row.get("tenant_code"))
                || !input.ownerEmail().equals(row.get("owner_email"))
                || !input.legalName().equals(row.get("legal_name"))
                || !input.displayName().equals(row.get("display_name"))
                || !input.timezone().equals(row.get("timezone"))
                || !input.currency().equals(row.get("default_currency"))
                || !input.ownerFullName().equals(row.get("owner_full_name"))
                || !input.operatorReference().equals(row.get("operator_reference"))
                || !input.reason().equals(row.get("reason"))) {
            throw new WmsTenantProvisioningRejectedException("Tenant provisioning operation reference does not match request");
        }
        UserStatus ownerStatus = UserStatus.valueOf((String) row.get("owner_status"));
        return new WmsTenantProvisioningResult(
                input.operationRef(), (String) row.get("tenant_public_id"), input.tenantCode(),
                (String) row.get("owner_user_public_id"), (String) row.get("membership_public_id"),
                (String) row.get("role_public_id"), ownerStatus == UserStatus.PENDING_VERIFICATION, true);
    }

    private Input validate(WmsTenantProvisioningProperties p) {
        if (p == null || !p.isEnabled()) {
            throw new WmsTenantProvisioningRejectedException("Controlled tenant provisioning is not explicitly enabled");
        }
        String tenantCode = required(p.getTenantCode(), "tenant code").toUpperCase(Locale.ROOT);
        if (!TENANT_CODE.matcher(tenantCode).matches()) {
            throw new WmsTenantProvisioningRejectedException("Tenant code is invalid");
        }
        String legalName = bounded(p.getLegalName(), "legal name", 2, 200);
        String displayName = bounded(p.getDisplayName(), "display name", 2, 150);
        String timezone = bounded(p.getTimezone(), "timezone", 1, 60);
        try {
            if (!ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new WmsTenantProvisioningRejectedException("Tenant timezone is invalid");
        }
        String currency = required(p.getCurrency(), "currency").toUpperCase(Locale.ROOT);
        try {
            if (!Currency.getInstance(currency).getCurrencyCode().equals(currency)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new WmsTenantProvisioningRejectedException("Tenant currency is invalid");
        }
        String email;
        try {
            email = WmsEmailNormalizer.normalize(p.getOwnerEmail());
        } catch (RuntimeException invalid) {
            throw new WmsTenantProvisioningRejectedException("Tenant owner email is invalid");
        }
        String ownerFullName = bounded(p.getOwnerFullName(), "owner full name", 2, 150);
        String operationRef = canonicalUuid(p.getOperationRef(), "operation reference");
        String operatorReference = safeReference(p.getOperatorReference(), "operator reference");
        String reason = bounded(p.getReason(), "provisioning reason", 10, 500);
        return new Input(tenantCode, legalName, displayName, timezone, currency,
                email, ownerFullName, operationRef, operatorReference, reason);
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new WmsTenantProvisioningRejectedException("Tenant provisioning could not be audited");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WmsTenantProvisioningRejectedException(field + " is required");
        }
        return value.trim();
    }

    private static String bounded(String value, String field, int min, int max) {
        String normalized = required(value, field);
        if (normalized.length() < min || normalized.length() > max) {
            throw new WmsTenantProvisioningRejectedException(field + " length is invalid");
        }
        return normalized;
    }

    private static String canonicalUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(required(value, field));
            if (!parsed.toString().equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException invalid) {
            throw new WmsTenantProvisioningRejectedException(field + " is invalid");
        }
    }

    private static String safeReference(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._:@/-]{3,120}")) {
            throw new WmsTenantProvisioningRejectedException(field + " is invalid");
        }
        return value;
    }

    private record Input(
            String tenantCode, String legalName, String displayName, String timezone, String currency,
            String ownerEmail, String ownerFullName, String operationRef,
            String operatorReference, String reason) {}

    private record OwnerIdentity(Long userId, String publicId, UserStatus status) {}
}

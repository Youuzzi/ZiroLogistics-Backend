package com.zirocraft.zirologistics.wms.bootstrap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Operator-only bootstrap for a completely empty WMS database.
 * It creates a tenant owner, not a global superuser or platform-wide role.
 */
public final class WmsPlatformBootstrapService {
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,63}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String OWNER_PERMISSION = "WMS_IDENTITY_MEMBERSHIP_INVITE";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;

    public WmsPlatformBootstrapService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
    }

    public Result bootstrap(WmsBootstrapProperties properties) {
        Input input = validate(properties);
        // Argon2id is deliberately performed before taking the singleton DB lock.
        String passwordHash = passwordEncoder.encode(input.password());
        String operationRef = UUID.randomUUID().toString();
        String tenantPublicId = UUID.randomUUID().toString();
        String userPublicId = UUID.randomUUID().toString();
        String membershipPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();

        try {
            return transaction.execute(status -> {
            String bootstrapStatus = jdbc.queryForObject(
                    "SELECT status FROM wms_platform_bootstrap_state WHERE id = 1 FOR UPDATE",
                    String.class);
            if (!"NOT_COMPLETED".equals(bootstrapStatus)) {
                throw new BootstrapRejectedException("WMS bootstrap has already been completed");
            }

            requireFreshDatabase();

            jdbc.update("""
                    INSERT INTO tenants
                      (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """,
                    tenantPublicId, input.tenantCode(), input.tenantLegalName(),
                    input.tenantDisplayName(), input.timezone(), input.currency());
            Long tenantId = jdbc.queryForObject(
                    "SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

            jdbc.update("""
                    INSERT INTO users
                      (public_id, email, password_hash, full_name, status, email_verified_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                    """,
                    userPublicId, input.email(), passwordHash, input.operatorFullName());
            Long userId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);

            jdbc.update("""
                    INSERT INTO tenant_memberships
                      (public_id, tenant_id, user_id, status, joined_at)
                    VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                    """,
                    membershipPublicId, tenantId, userId);
            Long membershipId = jdbc.queryForObject(
                    "SELECT id FROM tenant_memberships WHERE public_id = ?", Long.class, membershipPublicId);

            jdbc.update("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                    VALUES (?, ?, 'TENANT_OWNER', 'Tenant Owner',
                            'Initial tenant owner role created by controlled platform bootstrap', 1, 'ACTIVE')
                    """,
                    rolePublicId, tenantId);
            Long roleId = jdbc.queryForObject(
                    "SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);

            Long permissionId = jdbc.queryForObject(
                    "SELECT id FROM permissions WHERE permission_code = ?",
                    Long.class, OWNER_PERMISSION);
            jdbc.update("""
                    INSERT INTO membership_roles (tenant_id, membership_id, role_id)
                    VALUES (?, ?, ?)
                    """, tenantId, membershipId, roleId);
            jdbc.update("""
                    INSERT INTO role_permissions (role_id, permission_id)
                    VALUES (?, ?)
                    """, roleId, permissionId);

            int completed = jdbc.update("""
                    UPDATE wms_platform_bootstrap_state
                    SET status = 'COMPLETED', operation_ref = ?, completed_at = UTC_TIMESTAMP(6)
                    WHERE id = 1 AND status = 'NOT_COMPLETED'
                    """, operationRef);
            if (completed != 1) {
                throw new BootstrapRejectedException("WMS bootstrap completion could not be recorded");
            }

            return new Result(operationRef, tenantPublicId, userPublicId, membershipPublicId, rolePublicId);
            });
        } catch (BootstrapRejectedException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            // Do not propagate SQL diagnostics that may contain submitted identifiers.
            throw new BootstrapRejectedException("WMS bootstrap failed; no changes were committed");
        }
    }

    private void requireFreshDatabase() {
        requireZero("users");
        requireZero("tenants");
        requireZero("tenant_memberships");
        requireZero("roles");
    }

    private void requireZero(String table) {
        // Table names are private constants at call sites, never user input.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (count == null || count != 0) {
            throw new BootstrapRejectedException("WMS database is not an empty installation");
        }
    }

    private Input validate(WmsBootstrapProperties p) {
        if (p == null || !p.isEnabled()) {
            throw new BootstrapRejectedException("Controlled WMS bootstrap is not explicitly enabled");
        }
        String tenantCode = required(p.getTenantCode(), "tenant code").toUpperCase(Locale.ROOT);
        if (!TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("Invalid tenant code");
        }
        String legalName = bounded(p.getTenantLegalName(), "tenant legal name", 2, 200);
        String displayName = bounded(p.getTenantDisplayName(), "tenant display name", 2, 150);
        String timezone = bounded(p.getTimezone(), "timezone", 1, 60);
        ZoneId.of(timezone);
        String currency = required(p.getCurrency(), "currency").toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid ISO currency code");
        String email = required(p.getOperatorEmail(), "operator email").toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid operator email");
        }
        String fullName = bounded(p.getOperatorFullName(), "operator full name", 2, 150);
        String password = p.getOperatorPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Missing operator password");
        }
        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("Operator password must contain 12 to 128 characters");
        }
        return new Input(tenantCode, legalName, displayName, timezone, currency, email, fullName, password);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value.strip();
    }

    private static String bounded(String value, String field, int min, int max) {
        String normalized = required(value, field);
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }

    private record Input(String tenantCode, String tenantLegalName, String tenantDisplayName,
                         String timezone, String currency, String email, String operatorFullName,
                         String password) {}

    public record Result(String operationRef, String tenantPublicId, String userPublicId,
                         String membershipPublicId, String rolePublicId) {}

    public static final class BootstrapRejectedException extends RuntimeException {
        public BootstrapRejectedException(String message) { super(message); }
    }
}

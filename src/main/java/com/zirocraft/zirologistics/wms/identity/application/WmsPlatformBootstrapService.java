package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.authorization.domain.RoleStatus;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.MembershipRoleEntity;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.MembershipRoleId;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.MembershipRoleRepository;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.PermissionEntity;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.PermissionRepository;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.RoleEntity;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.RolePermissionEntity;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.RolePermissionId;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.RolePermissionRepository;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.RoleRepository;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One-time, operator-controlled creation of the first WMS tenant owner.
 *
 * A singleton V6 state row serializes concurrent attempts. The operation is
 * refused unless both users and tenants are empty, and all initial records plus
 * the completion marker are committed in one transaction.
 */
@Service
@Profile("wms")
public class WmsPlatformBootstrapService {

    public static final String INITIAL_OWNER_PERMISSION = "WMS_IDENTITY_MEMBERSHIP_INVITE";
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,63}");
    private static final int MIN_PASSWORD_CODE_POINTS = 12;
    private static final int MAX_PASSWORD_CODE_POINTS = 128;

    private final JdbcTemplate jdbcTemplate;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final MembershipRoleRepository membershipRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public WmsPlatformBootstrapService(
            JdbcTemplate jdbcTemplate,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            TenantMembershipRepository membershipRepository,
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            MembershipRoleRepository membershipRoleRepository,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, tenantRepository, userRepository, membershipRepository,
                roleRepository, permissionRepository, rolePermissionRepository, membershipRoleRepository,
                passwordEncoder, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    WmsPlatformBootstrapService(
            JdbcTemplate jdbcTemplate,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            TenantMembershipRepository membershipRepository,
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            MembershipRoleRepository membershipRoleRepository,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.tenantRepository = Objects.requireNonNull(tenantRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.permissionRepository = Objects.requireNonNull(permissionRepository);
        this.rolePermissionRepository = Objects.requireNonNull(rolePermissionRepository);
        this.membershipRoleRepository = Objects.requireNonNull(membershipRoleRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    public WmsPlatformBootstrapResult bootstrap(WmsPlatformBootstrapCommand command) {
        ValidatedCommand validated = validate(command);

        // Argon2id work is done before acquiring the singleton bootstrap row lock.
        String passwordHash = passwordEncoder.encode(validated.adminPassword());
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalStateException("WMS password encoder returned an invalid result");
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            // A current locking read both proves the singleton row exists and
            // serializes every bootstrap attempt before checking completion.
            LocalDateTime completedAt = jdbcTemplate.queryForObject("""
                    SELECT completed_at
                    FROM wms_platform_bootstrap_state
                    WHERE id = 1
                    FOR UPDATE
                    """, LocalDateTime.class);
            if (completedAt != null) {
                throw new IllegalStateException("WMS platform bootstrap has already completed");
            }

            long existingUsers = countRows("users");
            long existingTenants = countRows("tenants");
            if (existingUsers != 0 || existingTenants != 0) {
                throw new IllegalStateException("WMS platform bootstrap is only allowed on an empty WMS database");
            }

            PermissionEntity initialPermission = permissionRepository
                    .findByPermissionCode(INITIAL_OWNER_PERMISSION)
                    .orElseThrow(() -> new IllegalStateException(
                            "The initial WMS owner permission has not been seeded"));

            Instant now = clock.instant();
            LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

            TenantEntity tenant = new TenantEntity();
            tenant.setTenantCode(validated.tenantCode());
            tenant.setLegalName(validated.tenantLegalName());
            tenant.setDisplayName(validated.tenantDisplayName());
            tenant.setStatus(TenantStatus.ACTIVE);
            TenantEntity savedTenant = tenantRepository.saveAndFlush(tenant);

            UserEntity user = new UserEntity();
            user.setEmail(validated.adminEmail());
            user.setFullName(validated.adminFullName());
            user.setPasswordHash(passwordHash);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(nowUtc);
            UserEntity savedUser = userRepository.saveAndFlush(user);

            TenantMembershipEntity membership = new TenantMembershipEntity();
            membership.setTenantId(savedTenant.getId());
            membership.setUserId(savedUser.getId());
            membership.setStatus(MembershipStatus.ACTIVE);
            membership.setJoinedAt(nowUtc);
            TenantMembershipEntity savedMembership = membershipRepository.saveAndFlush(membership);

            RoleEntity ownerRole = new RoleEntity();
            ownerRole.setTenantId(savedTenant.getId());
            ownerRole.setRoleCode("TENANT_OWNER");
            ownerRole.setRoleName("Tenant Owner");
            ownerRole.setDescription(
                    "Initial owner created by controlled bootstrap; only explicitly seeded permissions are granted.");
            ownerRole.setSystemRole(true);
            ownerRole.setStatus(RoleStatus.ACTIVE);
            RoleEntity savedRole = roleRepository.saveAndFlush(ownerRole);

            RolePermissionEntity rolePermission = new RolePermissionEntity();
            rolePermission.setId(new RolePermissionId(savedRole.getId(), initialPermission.getId()));
            rolePermissionRepository.saveAndFlush(rolePermission);

            MembershipRoleEntity membershipRole = new MembershipRoleEntity();
            membershipRole.setId(new MembershipRoleId(savedMembership.getId(), savedRole.getId()));
            membershipRole.setTenantId(savedTenant.getId());
            membershipRoleRepository.saveAndFlush(membershipRole);

            int completed = jdbcTemplate.update("""
                    UPDATE wms_platform_bootstrap_state
                    SET bootstrap_tenant_id = ?,
                        bootstrap_user_id = ?,
                        completed_at = ?
                    WHERE id = 1
                      AND completed_at IS NULL
                    """, savedTenant.getId(), savedUser.getId(), nowUtc);
            if (completed != 1) {
                throw new IllegalStateException("WMS platform bootstrap state changed concurrently");
            }

            return new WmsPlatformBootstrapResult(
                    savedTenant.getPublicId(),
                    savedUser.getPublicId(),
                    savedMembership.getPublicId(),
                    savedRole.getPublicId());
        }), "WMS platform bootstrap transaction returned no result");
    }

    private long countRows(String tableName) {
        // Table names are fixed internal constants, never user supplied.
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0 : count;
    }

    private static ValidatedCommand validate(WmsPlatformBootstrapCommand command) {
        Objects.requireNonNull(command, "bootstrap command must not be null");

        String tenantCode = required(command.tenantCode(), 64, "tenantCode")
                .toUpperCase(Locale.ROOT);
        if (!TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("tenantCode must contain 3-64 uppercase letters, digits, '_' or '-'");
        }

        String legalName = required(command.tenantLegalName(), 200, "tenantLegalName");
        String displayName = required(command.tenantDisplayName(), 150, "tenantDisplayName");
        String adminEmail = WmsEmailNormalizer.normalize(command.adminEmail());
        String adminFullName = required(command.adminFullName(), 150, "adminFullName");

        String password = command.adminPassword();
        if (password == null) {
            throw new IllegalArgumentException("adminPassword is required");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MIN_PASSWORD_CODE_POINTS || codePoints > MAX_PASSWORD_CODE_POINTS
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("adminPassword does not meet the WMS password policy");
        }

        return new ValidatedCommand(
                tenantCode, legalName, displayName, adminEmail, adminFullName, password);
    }

    private static String required(String value, int maxLength, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    private record ValidatedCommand(
            String tenantCode,
            String tenantLegalName,
            String tenantDisplayName,
            String adminEmail,
            String adminFullName,
            String adminPassword) {
    }
}

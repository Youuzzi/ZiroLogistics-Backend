package com.zirocraft.zirologistics.wms.security.mfa.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues and consumes purpose-bound, single-use password reauthentication grants.
 *
 * Password hashing is verified before acquiring the user row lock. The user,
 * credential version, and selected active membership are then revalidated in
 * the same transaction as grant persistence/consumption.
 */
public final class WmsTenantMfaReauthenticationService {

    private static final Duration GRANT_TTL = Duration.ofMinutes(5);
    private static final int RANDOM_BYTES = 32;

    private final UserRepository userRepository;
    private final WmsMembershipAccessService membershipAccessService;
    private final PasswordEncoder passwordEncoder;
    private final WmsTenantMfaReauthenticationGrantStoragePort grantStorage;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public WmsTenantMfaReauthenticationService(
            UserRepository userRepository,
            WmsMembershipAccessService membershipAccessService,
            PasswordEncoder passwordEncoder,
            WmsTenantMfaReauthenticationGrantStoragePort grantStorage,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SecureRandom secureRandom) {
        this(userRepository, membershipAccessService, passwordEncoder, grantStorage,
                new TransactionTemplate(Objects.requireNonNull(transactionManager)), clock, secureRandom);
    }

    WmsTenantMfaReauthenticationService(
            UserRepository userRepository,
            WmsMembershipAccessService membershipAccessService,
            PasswordEncoder passwordEncoder,
            WmsTenantMfaReauthenticationGrantStoragePort grantStorage,
            TransactionTemplate transactionTemplate,
            Clock clock,
            SecureRandom secureRandom) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.membershipAccessService = Objects.requireNonNull(membershipAccessService);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.grantStorage = Objects.requireNonNull(grantStorage);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    /**
     * Verifies the current global-account password and issues a grant for the
     * exact active tenant membership and one explicitly allowed security purpose.
     */
    public WmsTenantMfaReauthenticationGrant issue(
            WmsAuthenticatedPrincipal principal,
            String currentPassword,
            WmsTenantMfaReauthenticationPurpose purpose) {
        validateRequest(principal, purpose);
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }

        UserEntity snapshot = userRepository.findByPublicId(principal.userPublicId())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(WmsTenantMfaReauthenticationRejectedException::new);
        if (!sameCredentialVersion(snapshot, principal)) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(currentPassword, snapshot.getPasswordHash());
        } catch (RuntimeException invalidHash) {
            passwordMatches = false;
        }
        if (!passwordMatches) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }

        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        String rawGrant = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random, (byte) 0);
        byte[] digest = digest(rawGrant);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(GRANT_TTL);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                UserEntity locked = userRepository.findByIdForUpdate(snapshot.getId())
                        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                        .orElseThrow(WmsTenantMfaReauthenticationRejectedException::new);
                if (!Objects.equals(locked.getVersion(), snapshot.getVersion())
                        || !Objects.equals(locked.getPasswordHash(), snapshot.getPasswordHash())
                        || !sameCredentialVersion(locked, principal)) {
                    throw new WmsTenantMfaReauthenticationRejectedException();
                }

                WmsMembershipAccess access = resolveCurrentMembership(principal);
                requireSameIdentity(access, principal);
                grantStorage.insert(UUID.randomUUID(), digest, access.userId(), access.tenantId(),
                        access.membershipId(), access.credentialVersion(), purpose, createdAt, expiresAt);
            });
        } finally {
            Arrays.fill(digest, (byte) 0);
        }

        return new WmsTenantMfaReauthenticationGrant(rawGrant, expiresAt);
    }

    /**
     * Consumes a grant atomically. Call this inside the same transaction as the
     * protected enrollment/revocation/recovery mutation; REQUIRED propagation
     * joins that transaction so the grant consumption rolls back with the action.
     */
    public void consume(
            WmsAuthenticatedPrincipal principal,
            String rawGrant,
            WmsTenantMfaReauthenticationPurpose purpose) {
        validateRequest(principal, purpose);
        byte[] digest = digestPresentedGrant(rawGrant);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                UserEntity user = userRepository.findByPublicId(principal.userPublicId())
                        .orElseThrow(WmsTenantMfaReauthenticationRejectedException::new);
                UserEntity locked = userRepository.findByIdForUpdate(user.getId())
                        .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                        .orElseThrow(WmsTenantMfaReauthenticationRejectedException::new);
                if (!sameCredentialVersion(locked, principal)) {
                    throw new WmsTenantMfaReauthenticationRejectedException();
                }

                WmsMembershipAccess access = resolveCurrentMembership(principal);
                requireSameIdentity(access, principal);
                boolean consumed = grantStorage.consume(digest, access.userId(), access.tenantId(),
                        access.membershipId(), access.credentialVersion(), purpose, clock.instant());
                if (!consumed) {
                    throw new WmsTenantMfaReauthenticationRejectedException();
                }
            });
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    /**
     * Revokes outstanding grants after a global account security change.
     * Invoke from the same transaction that changes the credential/security state.
     */
    public int revokeOutstandingForUser(long userId) {
        if (userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return transactionTemplate.execute(status ->
                grantStorage.revokeOutstandingForUser(userId, clock.instant()));
    }

    private WmsMembershipAccess resolveCurrentMembership(WmsAuthenticatedPrincipal principal) {
        try {
            return membershipAccessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        } catch (WmsMembershipAccessDeniedException denied) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }
    }

    private static void requireSameIdentity(
            WmsMembershipAccess access, WmsAuthenticatedPrincipal principal) {
        if (!Objects.equals(access.userPublicId(), principal.userPublicId())
                || !Objects.equals(access.tenantId(), principal.tenantId())
                || !Objects.equals(access.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(access.membershipId(), principal.membershipId())
                || !Objects.equals(access.membershipPublicId(), principal.membershipPublicId())
                || access.credentialVersion() == null
                || access.credentialVersion() != principal.credentialVersion()) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }
    }

    private static boolean sameCredentialVersion(UserEntity user, WmsAuthenticatedPrincipal principal) {
        return user.getCredentialVersion() != null
                && user.getCredentialVersion() == principal.credentialVersion();
    }

    private static void validateRequest(
            WmsAuthenticatedPrincipal principal, WmsTenantMfaReauthenticationPurpose purpose) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (purpose == null) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }
    }

    private static byte[] digestPresentedGrant(String rawGrant) {
        if (rawGrant == null || rawGrant.length() != 43) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawGrant);
            if (decoded.length != RANDOM_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(rawGrant)) {
                throw new WmsTenantMfaReauthenticationRejectedException();
            }
            Arrays.fill(decoded, (byte) 0);
            return digest(rawGrant);
        } catch (IllegalArgumentException malformed) {
            throw new WmsTenantMfaReauthenticationRejectedException();
        }
    }

    private static byte[] digest(String rawGrant) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update("zirologistics:wms:tenant-mfa:reauth-grant:v1:".getBytes(StandardCharsets.US_ASCII));
            return sha256.digest(rawGrant.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

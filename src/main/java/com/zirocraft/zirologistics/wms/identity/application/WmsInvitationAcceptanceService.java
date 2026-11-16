package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Accepts tenant invitations with a consistent tenant-then-token lock order.
 *
 * New-account password hashing is completed before opening the write
 * transaction. Token consumption, user creation, and membership activation
 * then commit or roll back together.
 */
@Service
@Profile("wms")
public class WmsInvitationAcceptanceService {

    private static final int MIN_PASSWORD_CODE_POINTS = 12;
    private static final int MAX_PASSWORD_CODE_POINTS = 128;
    private static final int MAX_FULL_NAME_LENGTH = 150;

    private final WmsIdentityActionTokenStoragePort tokenStorage;
    private final WmsIdentityActionTokenGenerator tokenGenerator;
    private final WmsUserLookupService userLookupService;
    private final UserRepository userRepository;
    private final TenantMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public WmsInvitationAcceptanceService(
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            WmsUserLookupService userLookupService,
            UserRepository userRepository,
            TenantMembershipRepository membershipRepository,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this(tokenStorage, tokenGenerator, userLookupService, userRepository, membershipRepository,
                passwordEncoder, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    WmsInvitationAcceptanceService(
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            WmsUserLookupService userLookupService,
            UserRepository userRepository,
            TenantMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.tokenStorage = Objects.requireNonNull(tokenStorage);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.userLookupService = Objects.requireNonNull(userLookupService);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Accepts an invitation for an address that does not yet have a global WMS user.
     * The invitation email link proves control of the target address; that address
     * is marked verified when the new account and membership are committed.
     */
    public WmsInvitationAcceptanceResult acceptForNewUser(
            String rawToken,
            String fullName,
            String rawPassword) {
        String normalizedName = validateFullName(fullName);
        validatePassword(rawPassword);

        byte[] digest = tokenGenerator.digestPresentedToken(rawToken);
        WmsIdentityActionTokenRecord preview = loadUsableInvitation(digest);
        if (preview.userId() != null) {
            throw new WmsIdentityActionTokenRejectedException();
        }

        String email = preview.targetEmail();
        if (userLookupService.existsByEmail(email)) {
            // An account may have been created after the invitation was issued.
            // Require that owner to authenticate instead of overwriting credentials.
            throw new WmsIdentityActionTokenRejectedException();
        }

        // Argon2id is intentionally outside the transaction to keep row locks short.
        String passwordHash = passwordEncoder.encode(rawPassword);
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalStateException("WMS password encoder returned an invalid result");
        }

        Instant now = clock.instant();
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            WmsIdentityActionTokenRecord token = lockAndValidateInvitation(preview, digest, now);
            if (token.userId() != null || userRepository.findByEmail(email).isPresent()) {
                throw new WmsIdentityActionTokenRejectedException();
            }

            UserEntity user = new UserEntity();
            user.setEmail(email);
            user.setFullName(normalizedName);
            user.setPasswordHash(passwordHash);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(toUtcLocalDateTime(now));
            UserEntity savedUser = userRepository.saveAndFlush(user);

            activateOrCreateMembership(token.tenantId(), savedUser.getId(), now);
            consumeToken(token.id(), now);

            return new WmsInvitationAcceptanceResult(token.tenantId(), email, true);
        }), "Invitation acceptance transaction returned no result");
    }

    /**
     * Accepts an invitation for an existing, already authenticated global user.
     * Existing credentials, profile, and verification metadata are never changed.
     */
    public WmsInvitationAcceptanceResult acceptForExistingUser(
            String rawToken,
            String authenticatedUserPublicId) {
        if (authenticatedUserPublicId == null || authenticatedUserPublicId.isBlank()) {
            throw new WmsIdentityActionTokenRejectedException();
        }

        byte[] digest = tokenGenerator.digestPresentedToken(rawToken);
        WmsIdentityActionTokenRecord preview = loadUsableInvitation(digest);
        String email = preview.targetEmail();
        Instant now = clock.instant();

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            WmsIdentityActionTokenRecord token = lockAndValidateInvitation(preview, digest, now);
            UserEntity user = userRepository.findByPublicId(authenticatedUserPublicId)
                    .orElseThrow(WmsIdentityActionTokenRejectedException::new);

            if (user.getStatus() != UserStatus.ACTIVE
                    || !email.equals(WmsEmailNormalizer.normalize(user.getEmail()))
                    || (token.userId() != null && !token.userId().equals(user.getId()))) {
                throw new WmsIdentityActionTokenRejectedException();
            }

            activateOrCreateMembership(token.tenantId(), user.getId(), now);
            consumeToken(token.id(), now);

            return new WmsInvitationAcceptanceResult(token.tenantId(), email, false);
        }), "Invitation acceptance transaction returned no result");
    }

    private WmsIdentityActionTokenRecord loadUsableInvitation(byte[] digest) {
        WmsIdentityActionTokenRecord preview = tokenStorage.findByDigest(
                        digest, WmsIdentityActionTokenPurpose.INVITATION)
                .orElseThrow(WmsIdentityActionTokenRejectedException::new);
        if (preview.tenantId() == null || preview.targetEmail() == null
                || !preview.isUsableAt(clock.instant())) {
            throw new WmsIdentityActionTokenRejectedException();
        }
        return preview;
    }

    /**
     * Maintains a single lock order for invitation issue and acceptance:
     * tenant row first, then the identity action token row.
     */
    private WmsIdentityActionTokenRecord lockAndValidateInvitation(
            WmsIdentityActionTokenRecord preview,
            byte[] digest,
            Instant now) {
        TenantStatus tenantStatus = tokenStorage.lockTenantForInvitation(preview.tenantId())
                .orElseThrow(WmsIdentityActionTokenRejectedException::new);
        if (tenantStatus != TenantStatus.ACTIVE) {
            throw new WmsIdentityActionTokenRejectedException();
        }

        WmsIdentityActionTokenRecord locked = tokenStorage.findByDigestForUpdate(
                        digest, WmsIdentityActionTokenPurpose.INVITATION)
                .orElseThrow(WmsIdentityActionTokenRejectedException::new);
        if (!locked.isUsableAt(now)
                || !Objects.equals(locked.tenantId(), preview.tenantId())
                || !Objects.equals(locked.targetEmail(), preview.targetEmail())) {
            throw new WmsIdentityActionTokenRejectedException();
        }
        return locked;
    }

    private void activateOrCreateMembership(long tenantId, long userId, Instant now) {
        Optional<TenantMembershipEntity> existing =
                membershipRepository.findByTenantIdAndUserId(tenantId, userId);
        if (existing.isPresent()) {
            TenantMembershipEntity membership = existing.get();
            if (membership.getStatus() != MembershipStatus.INVITED) {
                throw new WmsIdentityActionTokenRejectedException();
            }
            membership.setStatus(MembershipStatus.ACTIVE);
            membership.setJoinedAt(toUtcLocalDateTime(now));
            membershipRepository.saveAndFlush(membership);
            return;
        }

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setJoinedAt(toUtcLocalDateTime(now));
        membershipRepository.saveAndFlush(membership);
        // No role is assigned automatically. Tenant administrators must grant
        // an explicitly authorized role through the tenant's access-management flow.
    }

    private void consumeToken(long tokenId, Instant now) {
        if (tokenStorage.markConsumed(tokenId, now) != 1) {
            throw new WmsIdentityActionTokenRejectedException();
        }
    }

    private static String validateFullName(String fullName) {
        if (fullName == null) {
            throw new IllegalArgumentException("Full name is required");
        }
        String normalized = fullName.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_FULL_NAME_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Full name is invalid");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MIN_PASSWORD_CODE_POINTS || codePoints > MAX_PASSWORD_CODE_POINTS
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password does not meet the WMS password policy");
        }
    }

    private static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

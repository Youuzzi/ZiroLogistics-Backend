package com.zirocraft.zirologistics.wms.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues tenant-scoped invitations using the current persisted permission graph.
 *
 * The token digest and encrypted email payload are inserted in the same
 * transaction. The raw bearer token exists only in the encrypted outbox payload
 * and is never returned to the caller or written to logs.
 */
@Service
@Profile("wms")
public class WmsTenantInvitationService {

    public static final String INVITE_PERMISSION = "WMS_IDENTITY_MEMBERSHIP_INVITE";
    private static final Duration MINIMUM_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_TOKEN_TTL = Duration.ofDays(7);

    private final WmsMembershipAccessService membershipAccessService;
    private final WmsIdentityActionTokenStoragePort tokenStorage;
    private final WmsIdentityActionTokenGenerator tokenGenerator;
    private final WmsUserLookupService userLookupService;
    private final TenantMembershipRepository membershipRepository;
    private final ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider;
    private final WmsEmailOutboxStoragePort outboxStorage;
    private final ObjectMapper objectMapper;
    private final Duration invitationTokenTtl;
    private final Clock clock;

    @Autowired
    public WmsTenantInvitationService(
            WmsMembershipAccessService membershipAccessService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            WmsUserLookupService userLookupService,
            TenantMembershipRepository membershipRepository,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            @Value("${wms.identity.invitation.token-ttl:PT48H}") Duration invitationTokenTtl) {
        this(membershipAccessService, tokenStorage, tokenGenerator, userLookupService,
                membershipRepository, encryptionProvider, outboxStorage, objectMapper,
                invitationTokenTtl, Clock.systemUTC());
    }

    WmsTenantInvitationService(
            WmsMembershipAccessService membershipAccessService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            WmsUserLookupService userLookupService,
            TenantMembershipRepository membershipRepository,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            Duration invitationTokenTtl,
            Clock clock) {
        this.membershipAccessService = Objects.requireNonNull(membershipAccessService);
        this.tokenStorage = Objects.requireNonNull(tokenStorage);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.userLookupService = Objects.requireNonNull(userLookupService);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.encryptionProvider = Objects.requireNonNull(encryptionProvider);
        this.outboxStorage = Objects.requireNonNull(outboxStorage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.invitationTokenTtl = validateTtl(invitationTokenTtl);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public WmsInvitationIssueResult invite(WmsAuthenticatedPrincipal principal, String targetEmail) {
        Objects.requireNonNull(principal, "authenticated principal must not be null");

        WmsMembershipAccess access = membershipAccessService.resolve(
                principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(principal.tenantId(), access.tenantId())
                || !Objects.equals(principal.userPublicId(), access.userPublicId())
                || !Objects.equals(principal.membershipPublicId(), access.membershipPublicId())) {
            throw new WmsMembershipAccessDeniedException();
        }
        if (!access.hasPermission(INVITE_PERMISSION)) {
            throw new AccessDeniedException("Missing WMS tenant invitation permission");
        }

        String normalizedEmail = WmsEmailNormalizer.normalize(targetEmail);
        Instant now = clock.instant();

        TenantStatus tenantStatus = tokenStorage.lockTenantForInvitation(access.tenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant is unavailable for invitations"));
        if (tenantStatus != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is unavailable for invitations");
        }

        Optional<UserEntity> existingUser = userLookupService.findByEmail(normalizedEmail);
        Long existingUserId = null;
        if (existingUser.isPresent()) {
            UserEntity user = existingUser.get();
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new IllegalStateException("The target identity is not eligible for a tenant invitation");
            }
            existingUserId = user.getId();

            Optional<TenantMembershipEntity> existingMembership =
                    membershipRepository.findByTenantIdAndUserId(access.tenantId(), existingUserId);
            if (existingMembership.isPresent()
                    && existingMembership.get().getStatus() != MembershipStatus.INVITED) {
                throw new IllegalStateException("The target identity already has a tenant membership");
            }
        }

        WmsEmailOutboxEncryptionService encryption = encryptionProvider.getIfAvailable();
        if (encryption == null) {
            throw new IllegalStateException("WMS identity email encryption is not configured");
        }

        WmsGeneratedIdentityActionToken generatedToken = tokenGenerator.generate();
        Instant expiresAt = now.plus(invitationTokenTtl);
        UUID messageId = UUID.randomUUID();

        // Tenant-row lock serializes resend operations for the same tenant/email.
        tokenStorage.revokeOutstandingInvitations(access.tenantId(), normalizedEmail, now);
        long tokenId = tokenStorage.insert(
                generatedToken.digest(),
                WmsIdentityActionTokenPurpose.INVITATION,
                existingUserId,
                access.tenantId(),
                access.userId(),
                normalizedEmail,
                expiresAt,
                now);

        byte[] plaintextPayload = serializePayload(new InvitationEmailPayload(
                WmsIdentityActionTokenPurpose.INVITATION.name(),
                generatedToken.rawToken(),
                access.tenantId(),
                normalizedEmail,
                expiresAt));
        WmsEncryptedEmailPayload encryptedPayload = encryption.encrypt(
                messageId,
                WmsIdentityActionTokenPurpose.INVITATION,
                normalizedEmail,
                plaintextPayload);
        java.util.Arrays.fill(plaintextPayload, (byte) 0);

        outboxStorage.enqueue(
                messageId,
                WmsIdentityActionTokenPurpose.INVITATION,
                normalizedEmail,
                encryptedPayload.ciphertext(),
                encryptedPayload.nonce(),
                encryptedPayload.keyVersion(),
                now);

        return new WmsInvitationIssueResult(tokenId, expiresAt);
    }

    private byte[] serializePayload(InvitationEmailPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to prepare WMS invitation delivery payload");
        }
    }

    private static Duration validateTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "invitationTokenTtl must not be null");
        if (ttl.compareTo(MINIMUM_TOKEN_TTL) < 0 || ttl.compareTo(MAXIMUM_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("Invitation token TTL must be between 5 minutes and 7 days");
        }
        return ttl;
    }

    private record InvitationEmailPayload(
            String purpose,
            String actionToken,
            Long tenantId,
            String targetEmail,
            Instant expiresAt) {
    }
}

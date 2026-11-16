package com.zirocraft.zirologistics.wms.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Global-user email verification backed by single-use identity action tokens.
 *
 * User rows are locked before token rows for both issue and consume operations.
 * Verification, activation of a pending identity, and token consumption commit
 * in one transaction.
 */
@Service
@Profile("wms")
public class WmsEmailVerificationService {

    private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final WmsUserLookupService userLookupService;
    private final WmsIdentityActionTokenStoragePort tokenStorage;
    private final WmsIdentityActionTokenGenerator tokenGenerator;
    private final ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider;
    private final WmsEmailOutboxStoragePort outboxStorage;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public WmsEmailVerificationService(
            UserRepository userRepository,
            WmsUserLookupService userLookupService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(userRepository, userLookupService, tokenStorage, tokenGenerator,
                encryptionProvider, outboxStorage, objectMapper,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    WmsEmailVerificationService(
            UserRepository userRepository,
            WmsUserLookupService userLookupService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.userLookupService = Objects.requireNonNull(userLookupService);
        this.tokenStorage = Objects.requireNonNull(tokenStorage);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.encryptionProvider = Objects.requireNonNull(encryptionProvider);
        this.outboxStorage = Objects.requireNonNull(outboxStorage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Always returns the same result for unknown, ineligible, and already-verified
     * addresses. Delivery encryption is checked before account lookup.
     */
    public EmailVerificationRequestResult requestEmailVerification(String email) {
        WmsEmailOutboxEncryptionService encryption = requireEncryptionService();
        String normalizedEmail = WmsEmailNormalizer.normalize(email);
        Optional<UserEntity> candidate = userLookupService.findByEmail(normalizedEmail)
                .filter(this::isVerificationEligible);
        if (candidate.isEmpty()) {
            return EmailVerificationRequestResult.acceptedRequest();
        }

        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        UUID messageId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            UserEntity locked = userRepository.findByIdForUpdate(candidate.get().getId()).orElse(null);
            Instant now = clock.instant();
            if (locked == null || !isVerificationEligible(locked)
                    || !normalizedEmail.equals(WmsEmailNormalizer.normalize(locked.getEmail()))) {
                return null;
            }

            Instant expiresAt = now.plus(VERIFICATION_TOKEN_TTL);
            tokenStorage.revokeOutstandingForUser(
                    locked.getId(), WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION, now);
            tokenStorage.insert(
                    generated.digest(),
                    WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                    locked.getId(),
                    null,
                    null,
                    normalizedEmail,
                    expiresAt,
                    now);

            byte[] plaintext = serializePayload(new EmailVerificationPayload(
                    WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION.name(),
                    generated.rawToken(),
                    normalizedEmail,
                    expiresAt));
            WmsEncryptedEmailPayload encrypted;
            try {
                encrypted = encryption.encrypt(
                        messageId,
                        WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                        normalizedEmail,
                        plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }

            outboxStorage.enqueue(
                    messageId,
                    WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                    normalizedEmail,
                    encrypted.ciphertext(),
                    encrypted.nonce(),
                    encrypted.keyVersion(),
                    now);
            return null;
        });

        return EmailVerificationRequestResult.acceptedRequest();
    }

    /**
     * Verifies the current email snapshot and activates a pending global identity.
     */
    public void completeEmailVerification(String rawToken) {
        byte[] digest = tokenGenerator.digestPresentedToken(rawToken);
        WmsIdentityActionTokenRecord preview = tokenStorage.findByDigest(
                        digest, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION)
                .orElseThrow(WmsIdentityActionTokenRejectedException::new);
        if (preview.userId() == null || preview.targetEmail() == null
                || !preview.isUsableAt(clock.instant())) {
            throw new WmsIdentityActionTokenRejectedException();
        }

        Objects.requireNonNull(transactionTemplate.execute(status -> {
            UserEntity user = userRepository.findByIdForUpdate(preview.userId())
                    .orElseThrow(WmsIdentityActionTokenRejectedException::new);
            WmsIdentityActionTokenRecord token = tokenStorage.findByDigestForUpdate(
                            digest, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION)
                    .orElseThrow(WmsIdentityActionTokenRejectedException::new);
            Instant now = clock.instant();

            if (!token.isUsableAt(now)
                    || !Objects.equals(token.userId(), user.getId())
                    || !Objects.equals(token.targetEmail(), preview.targetEmail())
                    || !token.targetEmail().equals(WmsEmailNormalizer.normalize(user.getEmail()))
                    || !isVerificationEligible(user)) {
                throw new WmsIdentityActionTokenRejectedException();
            }

            user.setEmailVerifiedAt(toUtcLocalDateTime(now));
            if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                user.setStatus(UserStatus.ACTIVE);
            }
            userRepository.saveAndFlush(user);

            if (tokenStorage.markConsumed(token.id(), now) != 1) {
                throw new WmsIdentityActionTokenRejectedException();
            }
            tokenStorage.revokeOutstandingForUser(
                    user.getId(), WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION, now);
            return Boolean.TRUE;
        }), "Email verification transaction returned no result");
    }

    private boolean isVerificationEligible(UserEntity user) {
        return user.getEmailVerifiedAt() == null
                && (user.getStatus() == UserStatus.ACTIVE
                || user.getStatus() == UserStatus.PENDING_VERIFICATION);
    }

    private WmsEmailOutboxEncryptionService requireEncryptionService() {
        WmsEmailOutboxEncryptionService encryption = encryptionProvider.getIfAvailable();
        if (encryption == null) {
            throw new IllegalStateException("WMS identity email encryption is not configured");
        }
        return encryption;
    }

    private byte[] serializePayload(EmailVerificationPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to prepare WMS email verification delivery payload");
        }
    }

    private static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record EmailVerificationPayload(
            String purpose,
            String actionToken,
            String targetEmail,
            Instant expiresAt) {
    }
}

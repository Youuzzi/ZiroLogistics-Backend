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
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Global-account password change and reset workflows.
 *
 * Argon2id hashing occurs before write transactions. User-row locks are always
 * acquired before password-reset token rows, matching issuance and completion
 * lock order and preventing reset-token races.
 */
@Service
@Profile("wms")
public class WmsPasswordManagementService {

    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);
    private static final int MIN_PASSWORD_CODE_POINTS = 12;
    private static final int MAX_PASSWORD_CODE_POINTS = 128;

    private final UserRepository userRepository;
    private final WmsUserLookupService userLookupService;
    private final WmsIdentityActionTokenStoragePort tokenStorage;
    private final WmsIdentityActionTokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider;
    private final WmsEmailOutboxStoragePort outboxStorage;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Supplier<WmsTenantMfaReauthenticationService> reauthenticationServiceProvider;

    @Autowired
    public WmsPasswordManagementService(
            UserRepository userRepository,
            WmsUserLookupService userLookupService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            ObjectProvider<WmsTenantMfaReauthenticationService> reauthenticationServiceProvider) {
        this(userRepository, userLookupService, tokenStorage, tokenGenerator, passwordEncoder,
                encryptionProvider, outboxStorage, objectMapper,
                new TransactionTemplate(transactionManager), Clock.systemUTC(),
                reauthenticationServiceProvider::getIfAvailable);
    }

    WmsPasswordManagementService(
            UserRepository userRepository,
            WmsUserLookupService userLookupService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            PasswordEncoder passwordEncoder,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this(userRepository, userLookupService, tokenStorage, tokenGenerator, passwordEncoder,
                encryptionProvider, outboxStorage, objectMapper, transactionTemplate, clock, () -> null);
    }

    WmsPasswordManagementService(
            UserRepository userRepository,
            WmsUserLookupService userLookupService,
            WmsIdentityActionTokenStoragePort tokenStorage,
            WmsIdentityActionTokenGenerator tokenGenerator,
            PasswordEncoder passwordEncoder,
            ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider,
            WmsEmailOutboxStoragePort outboxStorage,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock,
            Supplier<WmsTenantMfaReauthenticationService> reauthenticationServiceProvider) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.userLookupService = Objects.requireNonNull(userLookupService);
        this.tokenStorage = Objects.requireNonNull(tokenStorage);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.encryptionProvider = Objects.requireNonNull(encryptionProvider);
        this.outboxStorage = Objects.requireNonNull(outboxStorage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.clock = Objects.requireNonNull(clock);
        this.reauthenticationServiceProvider = Objects.requireNonNull(reauthenticationServiceProvider);
    }

    /**
     * Changes a password for an authenticated global user. A stale password
     * snapshot cannot overwrite a concurrent password change.
     */
    public void changePassword(String authenticatedUserPublicId, String currentPassword, String newPassword) {
        if (authenticatedUserPublicId == null || authenticatedUserPublicId.isBlank()
                || currentPassword == null) {
            throw new BadCredentialsException("Current credentials are invalid");
        }
        validateNewPassword(newPassword);

        UserEntity snapshot = userRepository.findByPublicId(authenticatedUserPublicId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Current credentials are invalid"));
        String previousHash = snapshot.getPasswordHash();
        if (!passwordEncoder.matches(currentPassword, previousHash)) {
            throw new BadCredentialsException("Current credentials are invalid");
        }
        if (passwordEncoder.matches(newPassword, previousHash)) {
            throw new IllegalArgumentException("New password must differ from the current password");
        }

        // Expensive Argon2id work is performed before acquiring the user row lock.
        String newHash = passwordEncoder.encode(newPassword);
        if (newHash == null || newHash.isBlank()) {
            throw new IllegalStateException("WMS password encoder returned an invalid result");
        }

        Objects.requireNonNull(transactionTemplate.execute(status -> {
            UserEntity locked = userRepository.findByIdForUpdate(snapshot.getId())
                    .orElseThrow(() -> new BadCredentialsException("Current credentials are invalid"));
            if (locked.getStatus() != UserStatus.ACTIVE
                    || !Objects.equals(locked.getVersion(), snapshot.getVersion())
                    || !Objects.equals(locked.getPasswordHash(), previousHash)) {
                throw new IllegalStateException("Password changed concurrently; authenticate and retry");
            }

            Instant now = clock.instant();
            locked.setPasswordHash(newHash);
            locked.setCredentialVersion(Math.addExact(locked.getCredentialVersion(), 1L));
            userRepository.saveAndFlush(locked);
            tokenStorage.revokeOutstandingForUser(
                    locked.getId(), WmsIdentityActionTokenPurpose.PASSWORD_RESET, now);
            revokeTenantMfaReauthenticationGrants(locked.getId());
            return Boolean.TRUE;
        }), "Password change transaction returned no result");
    }

    /**
     * Requests a password reset without revealing whether the email exists.
     * Encryption is checked before account lookup so configuration errors do
     * not create an account-enumeration side channel.
     */
    public PasswordResetRequestResult requestPasswordReset(String email) {
        WmsEmailOutboxEncryptionService encryption = requireEncryptionService();
        String normalizedEmail = WmsEmailNormalizer.normalize(email);
        Optional<UserEntity> candidate = userLookupService.findByEmail(normalizedEmail)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (candidate.isEmpty()) {
            return PasswordResetRequestResult.acceptedRequest();
        }

        WmsGeneratedIdentityActionToken generatedToken = tokenGenerator.generate();
        UUID messageId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            UserEntity locked = userRepository.findByIdForUpdate(candidate.get().getId()).orElse(null);
            Instant now = clock.instant();
            if (locked == null || locked.getStatus() != UserStatus.ACTIVE
                    || !normalizedEmail.equals(WmsEmailNormalizer.normalize(locked.getEmail()))) {
                return null;
            }

            Instant expiresAt = now.plus(RESET_TOKEN_TTL);
            tokenStorage.revokeOutstandingForUser(
                    locked.getId(), WmsIdentityActionTokenPurpose.PASSWORD_RESET, now);
            tokenStorage.insert(
                    generatedToken.digest(),
                    WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                    locked.getId(),
                    null,
                    null,
                    normalizedEmail,
                    expiresAt,
                    now);

            byte[] plaintext = serializePayload(new PasswordResetEmailPayload(
                    WmsIdentityActionTokenPurpose.PASSWORD_RESET.name(),
                    generatedToken.rawToken(),
                    normalizedEmail,
                    expiresAt));
            WmsEncryptedEmailPayload encrypted;
            try {
                encrypted = encryption.encrypt(
                        messageId,
                        WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                        normalizedEmail,
                        plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }

            outboxStorage.enqueue(
                    messageId,
                    WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                    normalizedEmail,
                    encrypted.ciphertext(),
                    encrypted.nonce(),
                    encrypted.keyVersion(),
                    now);
            return null;
        });

        return PasswordResetRequestResult.acceptedRequest();
    }

    /**
     * Completes a password reset atomically with token consumption and revocation
     * of all other outstanding reset links for the same global user.
     */
    public void completePasswordReset(String rawToken, String newPassword) {
        validateNewPassword(newPassword);
        byte[] digest = tokenGenerator.digestPresentedToken(rawToken);
        WmsIdentityActionTokenRecord preview = tokenStorage.findByDigest(
                        digest, WmsIdentityActionTokenPurpose.PASSWORD_RESET)
                .orElseThrow(WmsIdentityActionTokenRejectedException::new);
        if (preview.userId() == null || preview.targetEmail() == null
                || !preview.isUsableAt(clock.instant())) {
            throw new WmsIdentityActionTokenRejectedException();
        }

        String newHash = passwordEncoder.encode(newPassword);
        if (newHash == null || newHash.isBlank()) {
            throw new IllegalStateException("WMS password encoder returned an invalid result");
        }

        Objects.requireNonNull(transactionTemplate.execute(status -> {
            // Global user row first, then token row: same order as reset issuance.
            UserEntity user = userRepository.findByIdForUpdate(preview.userId())
                    .orElseThrow(WmsIdentityActionTokenRejectedException::new);
            WmsIdentityActionTokenRecord token = tokenStorage.findByDigestForUpdate(
                            digest, WmsIdentityActionTokenPurpose.PASSWORD_RESET)
                    .orElseThrow(WmsIdentityActionTokenRejectedException::new);
            Instant now = clock.instant();

            if (!token.isUsableAt(now)
                    || !Objects.equals(token.userId(), user.getId())
                    || !Objects.equals(token.targetEmail(), preview.targetEmail())
                    || user.getStatus() != UserStatus.ACTIVE
                    || !token.targetEmail().equals(WmsEmailNormalizer.normalize(user.getEmail()))) {
                throw new WmsIdentityActionTokenRejectedException();
            }

            user.setPasswordHash(newHash);
            user.setCredentialVersion(Math.addExact(user.getCredentialVersion(), 1L));
            userRepository.saveAndFlush(user);
            if (tokenStorage.markConsumed(token.id(), now) != 1) {
                throw new WmsIdentityActionTokenRejectedException();
            }
            tokenStorage.revokeOutstandingForUser(
                    user.getId(), WmsIdentityActionTokenPurpose.PASSWORD_RESET, now);
            revokeTenantMfaReauthenticationGrants(user.getId());
            return Boolean.TRUE;
        }), "Password reset transaction returned no result");
    }

    private void revokeTenantMfaReauthenticationGrants(long userId) {
        WmsTenantMfaReauthenticationService reauthenticationService =
                reauthenticationServiceProvider.get();
        if (reauthenticationService != null) {
            reauthenticationService.revokeOutstandingForUser(userId);
        }
    }

    private WmsEmailOutboxEncryptionService requireEncryptionService() {
        WmsEmailOutboxEncryptionService encryption = encryptionProvider.getIfAvailable();
        if (encryption == null) {
            throw new IllegalStateException("WMS identity email encryption is not configured");
        }
        return encryption;
    }

    private byte[] serializePayload(PasswordResetEmailPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to prepare WMS password reset delivery payload");
        }
    }

    private static void validateNewPassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MIN_PASSWORD_CODE_POINTS || codePoints > MAX_PASSWORD_CODE_POINTS
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password does not meet the WMS password policy");
        }
    }

    private record PasswordResetEmailPayload(
            String purpose,
            String actionToken,
            String targetEmail,
            Instant expiresAt) {
    }
}

package com.zirocraft.zirologistics.wms.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsPasswordManagementMySqlIntegrationTest {

    private static final String TEST_AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_password_management_test")
            .withUsername("wms_password_management_test")
            .withPassword("wms_password_management_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("wms.identity.email.outbox.encryption.enabled", () -> true);
        registry.add("wms.identity.email.outbox.encryption.current-key-version", () -> 1);
        registry.add("wms.identity.email.outbox.encryption.keys[1]", () -> TEST_AES_KEY);
    }

    @Autowired private WmsPasswordManagementService passwordManagementService;
    @Autowired private WmsEmailVerificationService emailVerificationService;
    @Autowired private WmsEmailOutboxEncryptionService encryptionService;
    @Autowired private WmsIdentityActionTokenGenerator tokenGenerator;
    @Autowired private WmsIdentityActionTokenStoragePort tokenStorage;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private WmsMembershipAccessService membershipAccessService;
    @Autowired private WmsJwtAuthenticationConverter authenticationConverter;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void resetTokenIsEncryptedInOutboxAndConsumedWithPasswordUpdate() throws Exception {
        UserEntity user = createUser("reset-flow", "OriginalPassword123");

        PasswordResetRequestResult request = passwordManagementService.requestPasswordReset(user.getEmail());

        assertTrue(request.accepted());
        OutboxPayload outbox = latestOutbox(user.getEmail(), "PASSWORD_RESET");
        byte[] plaintext = decryptOutbox(outbox, user.getEmail(), WmsIdentityActionTokenPurpose.PASSWORD_RESET);
        JsonNode payload = objectMapper.readTree(plaintext);
        String rawToken = payload.get("actionToken").asText();
        assertEquals("PASSWORD_RESET", payload.get("purpose").asText());
        assertEquals(user.getEmail(), payload.get("targetEmail").asText());

        passwordManagementService.completePasswordReset(rawToken, "ResetPassword456");

        UserEntity updated = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("ResetPassword456", updated.getPasswordHash()));
        assertEquals(1L, updated.getCredentialVersion());
        assertFalse(passwordEncoder.matches("OriginalPassword123", updated.getPasswordHash()));

        WmsIdentityActionTokenRecord token = findToken(tokenGenerator.digestPresentedToken(rawToken));
        assertNotNull(token.consumedAt());
        assertNull(token.revokedAt());

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> passwordManagementService.completePasswordReset(rawToken, "AnotherPassword789"));
    }

    @Test
    void passwordChangeRevokesOutstandingResetTokens() throws Exception {
        UserEntity user = createUser("change-flow", "OriginalPassword123");
        passwordManagementService.requestPasswordReset(user.getEmail());

        OutboxPayload outbox = latestOutbox(user.getEmail(), "PASSWORD_RESET");
        byte[] plaintext = decryptOutbox(outbox, user.getEmail(), WmsIdentityActionTokenPurpose.PASSWORD_RESET);
        String rawToken = objectMapper.readTree(plaintext).get("actionToken").asText();

        passwordManagementService.changePassword(
                user.getPublicId(), "OriginalPassword123", "ChangedPassword456");

        UserEntity updated = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("ChangedPassword456", updated.getPasswordHash()));
        assertEquals(1L, updated.getCredentialVersion());

        WmsIdentityActionTokenRecord token = findToken(tokenGenerator.digestPresentedToken(rawToken));
        assertNull(token.consumedAt());
        assertNotNull(token.revokedAt());

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> passwordManagementService.completePasswordReset(rawToken, "ResetPassword789"));
    }

    @Test
    void passwordChangeInvalidatesPreviouslyIssuedAccessTokenWithoutWaitingForExpiry() {
        UserEntity user = createUser("credential-version", "OriginalPassword123");
        TenantEntity tenant = createTenant();
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        var before = membershipAccessService.resolve(user.getPublicId(), membership.getPublicId());
        assertEquals(0L, before.credentialVersion());

        Instant now = Instant.now();
        Jwt previouslyIssuedToken = Jwt.withTokenValue("old-access-token")
                .header("alg", "RS256")
                .subject(user.getPublicId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("token_use", "access")
                .claim("mid", membership.getPublicId())
                .claim("cv", before.credentialVersion())
                .build();

        assertTrue(authenticationConverter.convert(previouslyIssuedToken).isAuthenticated());

        passwordManagementService.changePassword(
                user.getPublicId(), "OriginalPassword123", "ChangedPassword456");

        UserEntity changed = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(1L, changed.getCredentialVersion());
        assertThrows(OAuth2AuthenticationException.class,
                () -> authenticationConverter.convert(previouslyIssuedToken));

        var after = membershipAccessService.resolve(user.getPublicId(), membership.getPublicId());
        Jwt newlyIssuedToken = Jwt.withTokenValue("new-access-token")
                .header("alg", "RS256")
                .subject(user.getPublicId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("token_use", "access")
                .claim("mid", membership.getPublicId())
                .claim("cv", after.credentialVersion())
                .build();
        assertTrue(authenticationConverter.convert(newlyIssuedToken).isAuthenticated());
    }

    @Test
    void emailVerificationActivatesPendingIdentityAndConsumesVerificationToken() throws Exception {
        UserEntity user = createPendingUser("email-verification");

        EmailVerificationRequestResult request =
                emailVerificationService.requestEmailVerification(user.getEmail());

        assertTrue(request.accepted());
        OutboxPayload outbox = latestOutbox(user.getEmail(), "EMAIL_VERIFICATION");
        byte[] plaintext = decryptOutbox(
                outbox, user.getEmail(), WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION);
        JsonNode payload = objectMapper.readTree(plaintext);
        String rawToken = payload.get("actionToken").asText();
        assertEquals("EMAIL_VERIFICATION", payload.get("purpose").asText());

        emailVerificationService.completeEmailVerification(rawToken);

        UserEntity verified = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, verified.getStatus());
        assertNotNull(verified.getEmailVerifiedAt());

        WmsIdentityActionTokenRecord token = findToken(tokenGenerator.digestPresentedToken(rawToken));
        assertNotNull(token.consumedAt());
        assertNull(token.revokedAt());
    }

    private TenantEntity createTenant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("CRED-" + suffix.toUpperCase());
        tenant.setLegalName("Credential Version Tenant " + suffix);
        tenant.setDisplayName("Credential Version " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.saveAndFlush(tenant);
    }

    private UserEntity createUser(String label, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setEmail(label + "." + UUID.randomUUID() + "@example.test");
        user.setFullName("Password Management " + label);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private UserEntity createPendingUser(String label) {
        UserEntity user = new UserEntity();
        user.setEmail(label + "." + UUID.randomUUID() + "@example.test");
        user.setFullName("Pending Verification " + label);
        user.setPasswordHash(passwordEncoder.encode("PendingPassword123"));
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        return userRepository.saveAndFlush(user);
    }

    private OutboxPayload latestOutbox(String email, String messageType) {
        return jdbcTemplate.queryForObject("""
                SELECT message_id, payload_ciphertext, payload_nonce, encryption_key_version
                FROM wms_identity_action_email_outbox
                WHERE message_type = ?
                  AND recipient_email = ?
                ORDER BY id DESC
                LIMIT 1
                """, (rs, row) -> new OutboxPayload(
                uuidFromBytes(rs.getBytes("message_id")),
                rs.getBytes("payload_ciphertext"),
                rs.getBytes("payload_nonce"),
                rs.getInt("encryption_key_version")), messageType, email);
    }

    private byte[] decryptOutbox(
            OutboxPayload outbox,
            String email,
            WmsIdentityActionTokenPurpose purpose) {
        return encryptionService.decrypt(
                outbox.messageId(),
                purpose,
                email,
                new WmsEncryptedEmailPayload(
                        outbox.keyVersion(), outbox.nonce(), outbox.ciphertext()));
    }

    private WmsIdentityActionTokenRecord findToken(byte[] digest) {
        return jdbcTemplate.queryForObject("""
                SELECT id, token_digest, purpose, user_id, tenant_id, invited_by_user_id,
                       target_email, expires_at, consumed_at, revoked_at, created_at
                FROM wms_identity_action_tokens
                WHERE token_digest = ?
                """, (rs, row) -> new WmsIdentityActionTokenRecord(
                rs.getLong("id"),
                rs.getBytes("token_digest"),
                WmsIdentityActionTokenPurpose.valueOf(rs.getString("purpose")),
                nullableLong(rs, "user_id"),
                nullableLong(rs, "tenant_id"),
                nullableLong(rs, "invited_by_user_id"),
                rs.getString("target_email"),
                toInstant(rs.getObject("expires_at", LocalDateTime.class)),
                toInstant(rs.getObject("consumed_at", LocalDateTime.class)),
                toInstant(rs.getObject("revoked_at", LocalDateTime.class)),
                toInstant(rs.getObject("created_at", LocalDateTime.class))), digest);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static UUID uuidFromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record OutboxPayload(UUID messageId, byte[] ciphertext, byte[] nonce, int keyVersion) {
    }
}

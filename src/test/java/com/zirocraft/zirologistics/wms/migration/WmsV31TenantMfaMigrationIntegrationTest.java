package com.zirocraft.zirologistics.wms.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV31TenantMfaMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v31_tenant_mfa_test")
            .withUsername("wms_v31_test")
            .withPassword("wms_v31_test");

    @Test
    void createsTenantMfaStorageWithIdentityBoundForeignKeysAndOneTimeStateConstraints() throws Exception {
        Flyway beforeV31 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("30"))
                .load();
        beforeV31.migrate();
        beforeV31.validate();

        int tablesBefore;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            tablesBefore = nonPlatformTableCount(statement);
        }

        Flyway current = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load();
        current.migrate();
        current.validate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEquals(tablesBefore + 6, nonPlatformTableCount(statement),
                    "V31 must add exactly six tenant MFA/security tables");
            assertEquals(3, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'tenant_memberships'
                      AND index_name = 'uk_membership_identity_scope'
                      AND non_unique = 0
                    """));
            assertEquals(6, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_type = 'BASE TABLE'
                      AND table_name IN (
                        'wms_tenant_mfa_factors',
                        'wms_tenant_mfa_recovery_codes',
                        'wms_tenant_mfa_step_up_challenges',
                        'wms_tenant_mfa_step_up_proofs',
                        'wms_tenant_mfa_throttle_buckets',
                        'tenant_security_audit_events'
                      )
                    """));

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO wms_tenant_mfa_throttle_buckets
                      (bucket_type, key_version, bucket_key_hash, window_started_at, attempt_count)
                    VALUES ('FACTOR', 1, ?, UTC_TIMESTAMP(6), 1)
                    """)) {
                insert.setBytes(1, randomDigest());
                assertEquals(1, insert.executeUpdate());
            }
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wms_tenant_mfa_throttle_buckets
                          (bucket_type, key_version, bucket_key_hash, window_started_at, attempt_count)
                        VALUES ('UNKNOWN', 1, ?, UTC_TIMESTAMP(6), 1)
                        """)) {
                    insert.setBytes(1, randomDigest());
                    insert.executeUpdate();
                }
            }, "Only reviewed MFA throttle bucket types are accepted");

            String tenantA = UUID.randomUUID().toString();
            String tenantB = UUID.randomUUID().toString();
            String userA = UUID.randomUUID().toString();
            String userB = UUID.randomUUID().toString();
            String membershipA = UUID.randomUUID().toString();
            String membershipB = UUID.randomUUID().toString();

            insertTenant(connection, tenantA, "MFA_A");
            insertTenant(connection, tenantB, "MFA_B");
            insertUser(connection, userA);
            insertUser(connection, userB);
            insertMembership(connection, membershipA, tenantA, userA);
            insertMembership(connection, membershipB, tenantB, userB);

            long userAId = id(statement, "users", "public_id", userA);
            long userBId = id(statement, "users", "public_id", userB);
            long tenantAId = id(statement, "tenants", "public_id", tenantA);
            long tenantBId = id(statement, "tenants", "public_id", tenantB);
            long membershipAId = id(statement, "tenant_memberships", "public_id", membershipA);

            String factorPublicId = UUID.randomUUID().toString();
            insertActiveFactor(connection, factorPublicId, userAId);

            byte[] recoveryDigest = new byte[32];
            recoveryDigest[0] = 17;
            insertRecoveryCode(connection, factorPublicId, userAId, recoveryDigest);
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM wms_tenant_mfa_recovery_codes
                    WHERE code_digest = UNHEX('%s')
                    """.formatted(hex(recoveryDigest))));

            String challengePublicId = UUID.randomUUID().toString();
            insertChallenge(connection, challengePublicId, randomDigest(),
                    userAId, tenantAId, membershipAId, "SINGLE_OPERATOR_ACTIVATION");
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM wms_tenant_mfa_step_up_challenges
                    WHERE public_id = '%s'
                    """.formatted(challengePublicId)));

            insertProof(connection, randomDigest(), userAId, tenantAId,
                    membershipAId, "SINGLE_OPERATOR_ACTIVATION");
            insertAuditEvent(connection, UUID.randomUUID().toString(), tenantAId,
                    membershipAId, userAId, userAId);

            assertThrows(SQLException.class, () -> insertChallenge(connection,
                    UUID.randomUUID().toString(), randomDigest(),
                    userAId, tenantBId, membershipAId, "SINGLE_OPERATOR_ACTIVATION"),
                    "A challenge cannot bind a membership to another tenant");

            assertThrows(SQLException.class, () -> insertRecoveryCodeWithWrongUser(
                    connection, factorPublicId, userBId, new byte[32]),
                    "A recovery code cannot bind a factor to another user");

            assertThrows(SQLException.class, () -> insertChallenge(connection,
                    UUID.randomUUID().toString(), randomDigest(),
                    userAId, tenantAId, membershipAId, "UNREVIEWED_PURPOSE"),
                    "Only reviewed step-up purposes are accepted");

            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wms_tenant_mfa_factors
                          (public_id, user_id, factor_type, status, last_accepted_time_step)
                        VALUES (?, ?, 'TOTP', 'ACTIVE', -1)
                        """)) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setLong(2, userAId);
                    insert.executeUpdate();
                }
            }, "An active factor must have an encrypted seed and key version");

            assertThrows(SQLException.class, () -> insertAuditEvent(connection,
                    UUID.randomUUID().toString(), tenantBId, membershipAId, userAId, userAId),
                    "A tenant security audit actor must match its tenant-scoped membership");
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static int nonPlatformTableCount(Statement statement) throws SQLException {
        return scalar(statement, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                  AND LEFT(table_name, 9) <> 'platform_'
                """);
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static long id(Statement statement, String table, String key, String value) throws SQLException {
        try (PreparedStatement query = statement.getConnection().prepareStatement(
                "SELECT id FROM " + table + " WHERE " + key + " = ?")) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static void insertTenant(Connection connection, String publicId, String code) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'MFA Test Legal', 'MFA Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """)) {
            insert.setString(1, publicId);
            insert.setString(2, code);
            insert.executeUpdate();
        }
    }

    private static void insertUser(Connection connection, String publicId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-only-hash', 'MFA Test User', 'ACTIVE')
                """)) {
            insert.setString(1, publicId);
            insert.setString(2, publicId + "@example.test");
            insert.executeUpdate();
        }
    }

    private static void insertMembership(
            Connection connection, String publicId, String tenantPublicId, String userPublicId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status)
                SELECT ?, t.id, u.id, 'ACTIVE'
                FROM tenants t JOIN users u ON u.public_id = ?
                WHERE t.public_id = ?
                """)) {
            insert.setString(1, publicId);
            insert.setString(2, userPublicId);
            insert.setString(3, tenantPublicId);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertActiveFactor(Connection connection, String publicId, long userId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO wms_tenant_mfa_factors
                  (public_id, user_id, factor_type, status, seed_nonce, encrypted_seed,
                   encryption_key_version, last_accepted_time_step)
                VALUES (?, ?, 'TOTP', 'ACTIVE', ?, ?, 'test-key-v1', -1)
                """)) {
            insert.setString(1, publicId);
            insert.setLong(2, userId);
            insert.setBytes(3, new byte[12]);
            insert.setBytes(4, new byte[] { 1, 2, 3, 4 });
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertRecoveryCode(
            Connection connection, String factorPublicId, long userId, byte[] digest) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO wms_tenant_mfa_recovery_codes (factor_id, user_id, code_digest, digest_key_version)
                SELECT f.id, f.user_id, ?, 'test-digest-v1'
                FROM wms_tenant_mfa_factors f
                WHERE f.public_id = ? AND f.user_id = ?
                """)) {
            insert.setBytes(1, digest);
            insert.setString(2, factorPublicId);
            insert.setLong(3, userId);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertRecoveryCodeWithWrongUser(
            Connection connection, String factorPublicId, long userId, byte[] digest) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO wms_tenant_mfa_recovery_codes (factor_id, user_id, code_digest, digest_key_version)
                VALUES ((SELECT id FROM wms_tenant_mfa_factors WHERE public_id = ?), ?, ?, 'test-digest-v1')
                """)) {
            insert.setString(1, factorPublicId);
            insert.setLong(2, userId);
            insert.setBytes(3, digest);
            insert.executeUpdate();
        }
    }

    private static void insertChallenge(
            Connection connection, String publicId, byte[] digest, long userId,
            long tenantId, long membershipId, String purpose) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO wms_tenant_mfa_step_up_challenges
                  (public_id, challenge_digest, user_id, tenant_id, membership_id,
                   credential_version, purpose, expires_at)
                VALUES (?, UNHEX(?), ?, ?, ?, 0, ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE))
                """)) {
            insert.setString(1, publicId);
            insert.setString(2, hex(digest));
            insert.setLong(3, userId);
            insert.setLong(4, tenantId);
            insert.setLong(5, membershipId);
            insert.setString(6, purpose);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertProof(
            Connection connection, byte[] digest, long userId,
            long tenantId, long membershipId, String purpose) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO wms_tenant_mfa_step_up_proofs
                  (proof_digest, user_id, tenant_id, membership_id, credential_version,
                   purpose, verified_at, expires_at)
                VALUES (UNHEX(?), ?, ?, ?, 0, ?, UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """)) {
            insert.setString(1, hex(digest));
            insert.setLong(2, userId);
            insert.setLong(3, tenantId);
            insert.setLong(4, membershipId);
            insert.setString(5, purpose);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertAuditEvent(
            Connection connection, String publicId, long tenantId,
            long membershipId, long actorUserId, long subjectUserId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO tenant_security_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_user_id, subject_user_id,
                   operation_ref, event_type, event_payload)
                VALUES (?, ?, ?, ?, ?, ?, 'MFA_ENROLLMENT_COMPLETED', JSON_OBJECT('factorType', 'TOTP'))
                """)) {
            insert.setString(1, publicId);
            insert.setLong(2, tenantId);
            insert.setLong(3, membershipId);
            insert.setLong(4, actorUserId);
            insert.setLong(5, subjectUserId);
            insert.setString(6, UUID.randomUUID().toString());
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static byte[] randomDigest() {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        return digest;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format("%02x", b));
        return value.toString();
    }
}

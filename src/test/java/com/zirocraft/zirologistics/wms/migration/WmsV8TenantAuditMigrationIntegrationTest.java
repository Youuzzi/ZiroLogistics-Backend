package com.zirocraft.zirologistics.wms.migration;

import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WmsV8TenantAuditMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v8_tenant_audit_test")
            .withUsername("wms_v8_test")
            .withPassword("wms_v8_test");

    @Test
    void createsTenantScopedAuditTableWithJsonPayloadAndCompositeActorConstraint() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load();
        flyway.migrate();
        flyway.validate();

        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String membership = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(77, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema=DATABASE() AND table_type='BASE TABLE' " +
                            "AND table_name <> 'flyway_schema_history' " +
                            "AND LEFT(table_name, 9) <> 'platform_'"));
            assertEquals("json", text(statement,
                    "SELECT data_type FROM information_schema.columns " +
                            "WHERE table_schema=DATABASE() AND table_name='tenant_audit_events' " +
                            "AND column_name='change_payload'"));

            insertTenant(connection, tenantA, "AUDIT_A");
            insertTenant(connection, tenantB, "AUDIT_B");
            insertUser(connection, user);
            insertMembership(connection, membership, tenantA, user);

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO tenant_audit_events
                      (public_id, tenant_id, actor_membership_id, actor_type, operation_ref, event_type, subject_public_id, change_payload)
                    SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'TENANT_PROFILE_UPDATED', t.public_id,
                           JSON_OBJECT('before', JSON_OBJECT('displayName', 'Before'),
                                       'after', JSON_OBJECT('displayName', 'After'))
                    FROM tenants t
                    JOIN tenant_memberships m ON m.public_id = ? AND m.tenant_id = t.id
                    WHERE t.public_id = ?
                    """)) {
                insert.setString(1, event);
                insert.setString(2, UUID.randomUUID().toString());
                insert.setString(3, membership);
                insert.setString(4, tenantA);
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM tenant_audit_events WHERE public_id='" + event + "'"));

            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO tenant_audit_events
                          (public_id, tenant_id, actor_membership_id, actor_type, operation_ref, event_type, subject_public_id, change_payload)
                        SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'TENANT_PROFILE_UPDATED', t.public_id, JSON_OBJECT('test', true)
                        FROM tenants t
                        JOIN tenant_memberships m ON m.public_id = ?
                        WHERE t.public_id = ?
                        """)) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setString(3, membership);
                    insert.setString(4, tenantB);
                    insert.executeUpdate();
                }
            });
        }
    }

    private static void insertTenant(Connection c, String publicId, String code) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Audit Test Legal', 'Audit Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """)) {
            s.setString(1, publicId);
            s.setString(2, code);
            s.executeUpdate();
        }
    }

    private static void insertUser(Connection c, String publicId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-only-hash', 'Audit Actor', 'ACTIVE')
                """)) {
            s.setString(1, publicId);
            s.setString(2, publicId + "@example.test");
            s.executeUpdate();
        }
    }

    private static void insertMembership(Connection c, String publicId, String tenantPublicId, String userPublicId)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status)
                SELECT ?, t.id, u.id, 'ACTIVE'
                FROM tenants t JOIN users u ON u.public_id = ?
                WHERE t.public_id = ?
                """)) {
            s.setString(1, publicId);
            s.setString(2, userPublicId);
            s.setString(3, tenantPublicId);
            s.executeUpdate();
        }
    }

    private static int scalar(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String text(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}

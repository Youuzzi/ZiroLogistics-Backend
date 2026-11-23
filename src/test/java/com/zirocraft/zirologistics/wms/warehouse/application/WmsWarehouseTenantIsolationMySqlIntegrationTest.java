package com.zirocraft.zirologistics.wms.warehouse.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsWarehouseTenantIsolationMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_wh_tenant_test")
            .withUsername("wms_wh_tenant_test")
            .withPassword("wms_wh_tenant_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsWarehouseManagementService warehouseService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void warehouseListAndDetailAreTenantScopedAndForeignLocationParentIsRejected() {
        Fixture tenantA = fixture("WH_A");
        Fixture tenantB = fixture("WH_B");
        String warehouseA = createWarehouse(tenantA);
        String warehouseB = createWarehouse(tenantB);
        String foreignParentLocation = createLocation(tenantB, warehouseB);
        assertEquals(1, count("SELECT COUNT(*) FROM warehouse_locations WHERE tenant_id=? AND public_id=?",
                tenantB.tenantId(), foreignParentLocation), "Foreign parent must exist before the rejection assertion");

        var tenantAWarehouses = warehouseService.listWarehouses(tenantA.principal());
        assertEquals(1, tenantAWarehouses.size(), "Tenant A must see only its own warehouse");
        assertNotNull(warehouseService.getWarehouse(tenantA.principal(), warehouseA));
        assertThrows(WmsWarehouseNotFoundException.class,
                () -> warehouseService.getWarehouse(tenantA.principal(), warehouseB));

        long tenantALocationsBefore = count("SELECT COUNT(*) FROM warehouse_locations WHERE tenant_id=? AND warehouse_id=(SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?)",
                tenantA.tenantId(), tenantA.tenantId(), warehouseA);
        assertThrows(WmsWarehouseNotFoundException.class,
                () -> warehouseService.createLocation(tenantA.principal(), warehouseA,
                        new WmsWarehouseManagementService.LocationCommand(
                                "BIN-CROSS-" + UUID.randomUUID().toString().substring(0, 8),
                                "Cross tenant parent attempt", "BIN", null, foreignParentLocation,
                                null, null, null, null, true, false, false, false, 10, 10)));
        assertEquals(tenantALocationsBefore,
                count("SELECT COUNT(*) FROM warehouse_locations WHERE tenant_id=? AND warehouse_id=(SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?)",
                        tenantA.tenantId(), tenantA.tenantId(), warehouseA),
                "Rejected foreign parent reference must not create a location for Tenant A");
    }

    private String createWarehouse(Fixture fixture) {
        String publicId = uuid();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, publicId, fixture.tenantId(), fixture.prefix() + "_WH", fixture.prefix() + " Warehouse");
        return publicId;
    }

    private String createLocation(Fixture fixture, String warehousePublicId) {
        long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?", Long.class,
                fixture.tenantId(), warehousePublicId);
        String publicId = uuid();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Foreign Parent', 'STORAGE', 'ACTIVE')
                """, publicId, fixture.tenantId(), warehouseId, fixture.prefix() + "_PARENT");
        return publicId;
    }

    private Fixture fixture(String prefix) {
        String tenantPublicId = uuid();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, prefix + "_" + uuid().replace("-", "").substring(0, 10),
                prefix + " Legal", prefix + " Tenant");
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id=?", Long.class, tenantPublicId);

        String userPublicId = uuid();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', ?, 'ACTIVE')
                """, userPublicId, prefix.toLowerCase() + "-" + uuid() + "@example.test", prefix + " User");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id=?", Long.class, userPublicId);

        String membershipPublicId = uuid();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject(
                "SELECT id FROM tenant_memberships WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, membershipPublicId);

        String rolePublicId = uuid();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, ?, ?, 'Warehouse isolation integration role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, prefix + "_ROLE", prefix + " Role");
        long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id=? AND public_id=?", Long.class, tenantId, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN ('WMS_WAREHOUSE_READ', 'WMS_LOCATION_CREATE')
                """, roleId);

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(prefix, tenantId, principal);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(String prefix, long tenantId, WmsAuthenticatedPrincipal principal) {}
}

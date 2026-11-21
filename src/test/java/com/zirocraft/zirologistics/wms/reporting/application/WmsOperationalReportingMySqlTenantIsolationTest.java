package com.zirocraft.zirologistics.wms.reporting.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsOperationalReportingMySqlTenantIsolationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_reporting_isolation_test")
            .withUsername("zirowms_reporting_isolation_test")
            .withPassword("zirowms_reporting_isolation_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsOperationalReportingService reportingService;
    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void operationalInventorySummaryIncludesOnlyTheAuthenticatedTenantsInventory() {
        Fixture tenantA = fixture("REPORT_A");
        Fixture tenantB = fixture("REPORT_B");

        receive(tenantA, "5.0000");
        receive(tenantB, "20.0000");

        Map<String, Object> summary = reportingService.operationalSummary(tenantA.principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> inventory = (Map<String, Object>) summary.get("inventoryPosition");

        assertEquals(new BigDecimal("5.0000"), inventory.get("onHandQty"));
        assertEquals(1L, ((Number) inventory.get("distinctItemCount")).longValue());
        assertEquals(1L, ((Number) inventory.get("warehousesWithInventoryCount")).longValue());
    }

    private void receive(Fixture f, String quantity) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), f.locationPublicId(), f.itemPublicId(),
                        null, null, null, "AVAILABLE", new BigDecimal(quantity))),
                "Reporting tenant isolation integration receipt"));
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
                VALUES (?, ?, ?, ?, 'Reporting integration role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, prefix + "_ROLE", prefix + " Role");
        long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id=? AND public_id=?", Long.class, tenantId, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN ('WMS_INVENTORY_POST', 'WMS_OPERATIONAL_REPORT_READ')
                """, roleId);

        String warehousePublicId = uuid();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, warehousePublicId, tenantId, prefix + "_WH", prefix + " Warehouse");
        long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, warehousePublicId);

        String locationPublicId = uuid();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, ?, 'STORAGE', 'ACTIVE')
                """, locationPublicId, tenantId, warehouseId, prefix + "_LOC", prefix + " Location");

        String itemPublicId = uuid();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, ?, (SELECT id FROM units_of_measure WHERE uom_code='EA'), 'NONE', 'ACTIVE')
                """, itemPublicId, tenantId, prefix + "_SKU", prefix + " Item");

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehousePublicId, locationPublicId, itemPublicId, principal);
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(long tenantId, String warehousePublicId, String locationPublicId,
                           String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}

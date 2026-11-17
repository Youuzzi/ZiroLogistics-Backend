package com.zirocraft.zirologistics.wms.outbound.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inbound.application.WmsInboundReceivingService;
import com.zirocraft.zirologistics.wms.picking.application.WmsPickingManagementService;
import com.zirocraft.zirologistics.wms.packing.application.WmsPackingManagementService;
import com.zirocraft.zirologistics.wms.shipping.application.WmsShippingManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsOutboundShipmentMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_outbound_test")
            .withUsername("zirowms_outbound_test")
            .withPassword("zirowms_outbound_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsOutboundShipmentService shipmentService;
    @Autowired private WmsInboundReceivingService inboundService;
    @Autowired private WmsPickingManagementService pickingService;
    @Autowired private WmsPackingManagementService packingService;
    @Autowired private WmsShippingManagementService shippingService;
    @Autowired private JdbcTemplate jdbc;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
        receive(fixture, "5.0000", List.of());
    }

    @Test
    void partialAllocationReservesStockAndReleaseRequiresFullAllocation() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");

        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("3.0000"), List.of()))));

        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(new BigDecimal("3.0000"), reserved(fixture));
        assertEquals(new BigDecimal("2.0000"), available(fixture));
        assertThrows(IllegalStateException.class, () -> shipmentService.release(fixture.principal(), shipmentId));

        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("2.0000"), List.of()))));
        assertEquals(new BigDecimal("5.0000"), reserved(fixture));
        assertEquals("RELEASED", shipmentService.release(fixture.principal(), shipmentId).get("status"));
        assertEquals("RELEASED", shipmentStatus(fixture, shipmentId));
    }

    @Test
    void overAllocationRollsBackReservationAndAllocationRows() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");

        assertThrows(IllegalArgumentException.class, () -> shipmentService.allocate(
                fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0001"), List.of())))));

        assertEquals(BigDecimal.ZERO.setScale(4), reserved(fixture));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM outbound_shipment_allocations a
                JOIN outbound_shipment_lines l
                  ON l.id = a.outbound_shipment_line_id AND l.tenant_id = a.tenant_id
                WHERE a.tenant_id = ? AND l.public_id = ?
                """, fixture.tenantId(), lineId));
    }

    @Test
    void serialAllocationMustMatchQuantityAndCannotBeAllocatedTwice() {
        Fixture serialFixture = fixture("SERIAL");
        receive(serialFixture, "2.0000", List.of("OUT-" + UUID.randomUUID(), "OUT-" + UUID.randomUUID()));
        List<String> serialIds = jdbc.queryForList("""
                SELECT public_id FROM inventory_serials
                WHERE tenant_id = ? ORDER BY serial_number
                """, String.class, serialFixture.tenantId());
        String firstShipment = createShipment(serialFixture);
        String firstLine = addLine(serialFixture, firstShipment, "1.0000");

        assertThrows(IllegalArgumentException.class, () -> shipmentService.allocate(
                serialFixture.principal(), firstShipment, firstLine,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                serialFixture.receivingLocationPublicId(), null, BigDecimal.ONE, List.of())))));

        shipmentService.allocate(serialFixture.principal(), firstShipment, firstLine,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                serialFixture.receivingLocationPublicId(), null, BigDecimal.ONE,
                                List.of(serialIds.getFirst())))));

        String secondShipment = createShipment(serialFixture);
        String secondLine = addLine(serialFixture, secondShipment, "1.0000");
        assertThrows(IllegalStateException.class, () -> shipmentService.allocate(
                serialFixture.principal(), secondShipment, secondLine,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                serialFixture.receivingLocationPublicId(), null, BigDecimal.ONE,
                                List.of(serialIds.getFirst()))))));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM outbound_shipment_line_serials
                WHERE tenant_id = ? AND serial_id =
                  (SELECT id FROM inventory_serials WHERE tenant_id = ? AND public_id = ?)
                  AND status = 'ALLOCATED'
                """, serialFixture.tenantId(), serialFixture.tenantId(), serialIds.getFirst()));
    }


    @Test
    void pickingConsumesReservationAndMovesStockToStagingAtomically() {
        String shipmentId=createShipment(fixture);
        String lineId=addLine(fixture,shipmentId,"5.0000");
        shipmentService.allocate(fixture.principal(),shipmentId,lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(),null,new BigDecimal("5.0000"),List.of()))));
        shipmentService.release(fixture.principal(),shipmentId);
        String taskId=(String)pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-"+UUID.randomUUID().toString().substring(0,8),"NORMAL",null)).get("public_id");
        pickingService.assign(fixture.principal(),taskId,
                new WmsPickingManagementService.AssignPickingCommand(fixture.membershipPublicId()));
        pickingService.start(fixture.principal(),taskId);

        String staging=UUID.randomUUID().toString();
        long warehouseId=jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class,fixture.tenantId(),fixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Staging','STAGING','ACTIVE')
                """,staging,fixture.tenantId(),warehouseId,"STG_"+UUID.randomUUID().toString().substring(0,8));

        pickingService.confirm(fixture.principal(),taskId,1,
                new WmsPickingManagementService.ConfirmPickingCommand(UUID.randomUUID(),
                        new BigDecimal("2.0000"),staging,List.of(),null));
        assertEquals(new BigDecimal("3.0000"),reserved(fixture));
        assertEquals(new BigDecimal("5.0000"),onHand(fixture));
        assertEquals(new BigDecimal("2.0000"),jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty),0.0000) FROM inventory_balances
                WHERE tenant_id=? AND location_id=(SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?)
                """,BigDecimal.class,fixture.tenantId(),fixture.tenantId(),staging));

        Map<String,Object> completed=pickingService.confirm(fixture.principal(),taskId,1,
                new WmsPickingManagementService.ConfirmPickingCommand(UUID.randomUUID(),
                        new BigDecimal("3.0000"),staging,List.of(),null));
        assertEquals("COMPLETED",completed.get("status"));
        assertEquals("PICKED",shipmentStatus(fixture,shipmentId));
        assertEquals(BigDecimal.ZERO.setScale(4),reserved(fixture));
        assertEquals(new BigDecimal("5.0000"),onHand(fixture));
    }

    @Test
    void packingRequiresPickedQuantitiesAndSealedPackagesBeforeShipmentBecomesPacked() {
        String shipmentId=createShipment(fixture);
        String lineId=addLine(fixture,shipmentId,"5.0000");
        shipmentService.allocate(fixture.principal(),shipmentId,lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(),null,new BigDecimal("5.0000"),List.of()))));
        shipmentService.release(fixture.principal(),shipmentId);
        String taskId=(String)pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-"+UUID.randomUUID().toString().substring(0,8),"NORMAL",null)).get("public_id");
        pickingService.assign(fixture.principal(),taskId,
                new WmsPickingManagementService.AssignPickingCommand(fixture.membershipPublicId()));
        pickingService.start(fixture.principal(),taskId);
        String staging=UUID.randomUUID().toString();
        long warehouseId=jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class,fixture.tenantId(),fixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Staging','STAGING','ACTIVE')
                """,staging,fixture.tenantId(),warehouseId,"STG_"+UUID.randomUUID().toString().substring(0,8));
        pickingService.confirm(fixture.principal(),taskId,1,
                new WmsPickingManagementService.ConfirmPickingCommand(UUID.randomUUID(),
                        new BigDecimal("5.0000"),staging,List.of(),null));

        int ledgerBefore=count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?",fixture.tenantId());
        String sessionId=(String)packingService.create(fixture.principal(),
                new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                        "PACK-"+UUID.randomUUID().toString().substring(0,8),null)).get("public_id");
        assertEquals("DRAFT",packingService.get(fixture.principal(),sessionId).get("status"));
        packingService.ready(fixture.principal(),sessionId);
        packingService.assign(fixture.principal(),sessionId,
                new WmsPackingManagementService.AssignSessionCommand(fixture.membershipPublicId()));
        packingService.start(fixture.principal(),sessionId);
        String packageId=(String)packingService.createPackage(fixture.principal(),sessionId,
                new WmsPackingManagementService.CreatePackageCommand(
                        "BOX-"+UUID.randomUUID().toString().substring(0,8),"CARTON",
                        null,null,null,null,null)).get("public_id");

        assertThrows(IllegalStateException.class,()->packingService.complete(fixture.principal(),sessionId));
        packingService.addLine(fixture.principal(),sessionId,packageId,
                new WmsPackingManagementService.AddPackageLineCommand(UUID.randomUUID(),1,
                        new BigDecimal("5.0000"),List.of(),null));
        packingService.sealPackage(fixture.principal(),sessionId,packageId);
        Map<String,Object> completed=packingService.complete(fixture.principal(),sessionId);

        assertEquals("COMPLETED",completed.get("status"));
        assertEquals("PACKED",shipmentStatus(fixture,shipmentId));
        assertEquals(ledgerBefore,count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?",fixture.tenantId()));
        assertEquals(new BigDecimal("5.0000"),onHand(fixture));

        int ledgerBeforeShipping=count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?",fixture.tenantId());
        Map<String,Object> shipped=shippingService.ship(fixture.principal(),shipmentId,
                new WmsShippingManagementService.ShipCommand(UUID.randomUUID(),null,"TRACK-"+UUID.randomUUID(),null));
        assertEquals("SHIPPED",shipped.get("status"));
        assertEquals("SHIPPED",shipmentStatus(fixture,shipmentId));
        assertEquals(BigDecimal.ZERO.setScale(4),onHand(fixture));
        assertEquals(ledgerBeforeShipping+1,count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?",fixture.tenantId()));
        assertEquals(1,count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id=? AND movement_type='ISSUE' AND quantity_delta<0
                """,fixture.tenantId()));
        assertThrows(IllegalStateException.class,()->shippingService.ship(fixture.principal(),shipmentId,
                new WmsShippingManagementService.ShipCommand(UUID.randomUUID(),null,null,null)));
    }

    @Test
    void packingPreservesPickedSerialTraceabilityAndPreventsPackingTheSameSerialTwice() {
        Fixture serialFixture=fixture("SERIAL");
        String firstSerialNumber="PACK-SERIAL-"+UUID.randomUUID();
        String secondSerialNumber="PACK-SERIAL-"+UUID.randomUUID();
        receive(serialFixture,"2.0000",List.of(firstSerialNumber,secondSerialNumber));
        List<String> serialPublicIds=jdbc.queryForList("""
                SELECT public_id FROM inventory_serials WHERE tenant_id=? ORDER BY serial_number
                """,String.class,serialFixture.tenantId());
        String shipmentId=createShipment(serialFixture);
        String lineId=addLine(serialFixture,shipmentId,"2.0000");
        shipmentService.allocate(serialFixture.principal(),shipmentId,lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                serialFixture.receivingLocationPublicId(),null,new BigDecimal("2.0000"),serialPublicIds))));
        shipmentService.release(serialFixture.principal(),shipmentId);
        String taskId=(String)pickingService.create(serialFixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-"+UUID.randomUUID().toString().substring(0,8),"NORMAL",null)).get("public_id");
        pickingService.assign(serialFixture.principal(),taskId,
                new WmsPickingManagementService.AssignPickingCommand(serialFixture.membershipPublicId()));
        pickingService.start(serialFixture.principal(),taskId);
        String staging=UUID.randomUUID().toString();
        long warehouseId=jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class,serialFixture.tenantId(),serialFixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Staging','STAGING','ACTIVE')
                """,staging,serialFixture.tenantId(),warehouseId,"STG_"+UUID.randomUUID().toString().substring(0,8));
        pickingService.confirm(serialFixture.principal(),taskId,1,
                new WmsPickingManagementService.ConfirmPickingCommand(UUID.randomUUID(),
                        new BigDecimal("2.0000"),staging,serialPublicIds,null));

        String sessionId=(String)packingService.create(serialFixture.principal(),
                new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                        "PACK-"+UUID.randomUUID().toString().substring(0,8),null)).get("public_id");
        packingService.ready(serialFixture.principal(),sessionId);
        packingService.assign(serialFixture.principal(),sessionId,
                new WmsPackingManagementService.AssignSessionCommand(serialFixture.membershipPublicId()));
        packingService.start(serialFixture.principal(),sessionId);
        String packageId=(String)packingService.createPackage(serialFixture.principal(),sessionId,
                new WmsPackingManagementService.CreatePackageCommand(
                        "BOX-"+UUID.randomUUID().toString().substring(0,8),"CARTON",
                        null,null,null,null,null)).get("public_id");
        packingService.addLine(serialFixture.principal(),sessionId,packageId,
                new WmsPackingManagementService.AddPackageLineCommand(UUID.randomUUID(),1,
                        BigDecimal.ONE,List.of(serialPublicIds.getFirst()),null));
        assertEquals(1,count("""
                SELECT COUNT(*) FROM packing_package_serials ps
                JOIN inventory_serials s ON s.id=ps.serial_id AND s.tenant_id=ps.tenant_id
                WHERE ps.tenant_id=? AND s.public_id=?
                """,serialFixture.tenantId(),serialPublicIds.getFirst()));
        assertThrows(IllegalStateException.class,()->packingService.addLine(serialFixture.principal(),
                sessionId,packageId,new WmsPackingManagementService.AddPackageLineCommand(
                        UUID.randomUUID(),1,BigDecimal.ONE,List.of(serialPublicIds.getFirst()),null)));
        packingService.addLine(serialFixture.principal(),sessionId,packageId,
                new WmsPackingManagementService.AddPackageLineCommand(UUID.randomUUID(),1,
                        BigDecimal.ONE,List.of(serialPublicIds.get(1)),null));
        assertEquals(2,count("SELECT COUNT(*) FROM packing_package_serials WHERE tenant_id=?",
                serialFixture.tenantId()));
        packingService.sealPackage(serialFixture.principal(),sessionId,packageId);
        assertEquals("COMPLETED",packingService.complete(serialFixture.principal(),sessionId).get("status"));
        assertEquals("PACKED",shipmentStatus(serialFixture,shipmentId));

        Map<String,Object> shipped=shippingService.ship(serialFixture.principal(),shipmentId,
                new WmsShippingManagementService.ShipCommand(UUID.randomUUID(),null,null,null));
        assertEquals("SHIPPED",shipped.get("status"));
        assertEquals(2,count("""
                SELECT COUNT(*) FROM inventory_serials
                WHERE tenant_id=? AND status='SHIPPED'
                """,serialFixture.tenantId()));
    }

    private String createShipment(Fixture f) {
        return (String) shipmentService.create(f.principal(),
                new WmsOutboundShipmentService.CreateShipmentCommand(
                        f.warehousePublicId(), f.customerPublicId(), null,
                        "OUT-" + UUID.randomUUID().toString().substring(0, 12),
                        null, "SALES", null, null)).get("public_id");
    }

    private String addLine(Fixture f, String shipmentId, String qty) {
        return (String) shipmentService.addLine(f.principal(), shipmentId,
                new WmsOutboundShipmentService.ShipmentLineCommand(
                        f.itemPublicId(), new BigDecimal(qty), null)).get("public_id");
    }

    private void receive(Fixture f, String qty, List<String> serialNumbers) {
        String receiptId = (String) inboundService.create(f.principal(),
                new WmsInboundReceivingService.ReceiptCommand(
                        f.warehousePublicId(), f.supplierPublicId(), null, f.receivingLocationPublicId(),
                        "IN-" + UUID.randomUUID().toString().substring(0, 12),
                        null, "PURCHASE", null, null)).get("public_id");
        String lineId = (String) inboundService.addLine(f.principal(), receiptId,
                new WmsInboundReceivingService.ReceiptLineCommand(
                        f.itemPublicId(), new BigDecimal(qty), null)).get("public_id");
        List<WmsInboundReceivingService.SerialReceipt> serials = serialNumbers.stream()
                .map(value -> new WmsInboundReceivingService.SerialReceipt(value, null)).toList();
        inboundService.post(f.principal(), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal(qty), new BigDecimal(qty), BigDecimal.ZERO,
                                null, List.of(), serials))));
    }

    private BigDecimal onHand(Fixture f) {
        return balance(f, "on_hand_qty");
    }

    private BigDecimal reserved(Fixture f) {
        return balance(f, "reserved_qty");
    }

    private BigDecimal available(Fixture f) {
        return balance(f, "available_qty");
    }

    private BigDecimal balance(Fixture f, String column) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.%s), 0.0000)
                FROM inventory_balances b
                WHERE b.tenant_id = ? AND b.item_id =
                  (SELECT id FROM items WHERE tenant_id = ? AND public_id = ?)
                """.formatted(column), BigDecimal.class,
                f.tenantId(), f.tenantId(), f.itemPublicId());
    }

    private String shipmentStatus(Fixture f, String publicId) {
        return jdbc.queryForObject("SELECT status FROM outbound_shipments WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), publicId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Fixture fixture(String tracking) {
        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Outbound Test Legal', 'Outbound Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "OB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Outbound Test User', 'ACTIVE')
                """, userPublicId, "outbound-" + UUID.randomUUID() + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);
        String membershipPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                Long.class, membershipPublicId);

        String rolePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, ?, 'Outbound Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "OB_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INVENTORY_POST', 'WMS_INVENTORY_RESERVE',
                  'WMS_INBOUND_RECEIPT_READ', 'WMS_INBOUND_RECEIPT_CREATE',
                  'WMS_INBOUND_RECEIPT_UPDATE', 'WMS_INBOUND_RECEIPT_POST',
                  'WMS_OUTBOUND_SHIPMENT_READ', 'WMS_OUTBOUND_SHIPMENT_CREATE',
                  'WMS_OUTBOUND_SHIPMENT_UPDATE', 'WMS_OUTBOUND_SHIPMENT_ALLOCATE',
                  'WMS_OUTBOUND_SHIPMENT_RELEASE',
                  'WMS_PICKING_READ', 'WMS_PICKING_CREATE', 'WMS_PICKING_ASSIGN',
                  'WMS_PICKING_START', 'WMS_PICKING_CONFIRM',
                  'WMS_PACKING_READ', 'WMS_PACKING_CREATE', 'WMS_PACKING_READY',
                  'WMS_PACKING_ASSIGN', 'WMS_PACKING_START', 'WMS_PACKING_PACKAGE',
                  'WMS_PACKING_SEAL', 'WMS_PACKING_COMPLETE',
                  'WMS_SHIPPING_READ', 'WMS_SHIPPING_SHIP'
                )
                """, roleId);

        String warehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Outbound Test Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "WH_" + UUID.randomUUID().toString().substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?", Long.class, warehousePublicId);

        String receivingLocationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type,
                   is_receivable, allow_mixed_skus, allow_mixed_lots, status)
                VALUES (?, ?, ?, ?, 'Receiving', 'RECEIVING', 1, 1, 1, 'ACTIVE')
                """, receivingLocationPublicId, tenantId, warehouseId,
                "RCV_" + UUID.randomUUID().toString().substring(0, 8));

        String supplierPublicId = partner(tenantId, "SUPPLIER", "Supplier");
        String customerPublicId = partner(tenantId, "CUSTOMER", "Customer");
        String itemPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Outbound Test Item', (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, itemPublicId, tenantId, "OB_" + UUID.randomUUID().toString().substring(0, 12), tracking);
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(userPublicId, tenantId,
                tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehousePublicId, receivingLocationPublicId,
                supplierPublicId, customerPublicId, itemPublicId, membershipPublicId, principal);
    }

    private String partner(long tenantId, String role, String name) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO business_partners (public_id, tenant_id, partner_code, partner_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, publicId, tenantId, role.substring(0, 3) + "_" + UUID.randomUUID().toString().substring(0, 8),
                "Outbound Test " + name);
        long id = jdbc.queryForObject("SELECT id FROM business_partners WHERE public_id = ?", Long.class, publicId);
        jdbc.update("INSERT INTO business_partner_roles (tenant_id, business_partner_id, role_type) VALUES (?, ?, ?)",
                tenantId, id, role);
        return publicId;
    }

    private record Fixture(long tenantId, String warehousePublicId, String receivingLocationPublicId,
                           String supplierPublicId, String customerPublicId, String itemPublicId,
                           String membershipPublicId, WmsAuthenticatedPrincipal principal) {}
}

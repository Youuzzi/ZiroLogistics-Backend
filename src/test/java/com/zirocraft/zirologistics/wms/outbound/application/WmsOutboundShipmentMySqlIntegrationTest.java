package com.zirocraft.zirologistics.wms.outbound.application;

import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inbound.application.WmsInboundReceivingService;
import com.zirocraft.zirologistics.wms.picking.application.WmsPickingManagementService;
import com.zirocraft.zirologistics.wms.packing.application.WmsPackingManagementService;
import com.zirocraft.zirologistics.wms.shipping.application.WmsShippingManagementService;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;

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
    @Autowired private WmsInterWarehouseTransferService transferService;
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
        UUID pickingRequestId = UUID.randomUUID();
        pickingService.confirm(fixture.principal(),taskId,1,
                new WmsPickingManagementService.ConfirmPickingCommand(pickingRequestId,
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
        UUID shippingRequestId = UUID.randomUUID();
        Map<String,Object> shipped=shippingService.ship(fixture.principal(),shipmentId,
                new WmsShippingManagementService.ShipCommand(shippingRequestId,null,"TRACK-"+UUID.randomUUID(),null));
        assertEquals("SHIPPED",shipped.get("status"));
        assertEquals("SHIPPED",shipmentStatus(fixture,shipmentId));
        assertEquals(BigDecimal.ZERO.setScale(4),onHand(fixture));
        assertEquals(ledgerBeforeShipping+1,count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?",fixture.tenantId()));
        assertEquals(1,count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id=? AND movement_type='ISSUE' AND quantity_delta<0
                """,fixture.tenantId()));
        assertThrows(IllegalStateException.class,()->shippingService.ship(fixture.principal(),shipmentId,
                new WmsShippingManagementService.ShipCommand(shippingRequestId,null,null,null)));

        long actorMembershipId = jdbc.queryForObject(
                "SELECT id FROM tenant_memberships WHERE tenant_id=? AND public_id=?",
                Long.class, fixture.tenantId(), fixture.membershipPublicId());
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND subject_public_id=? AND event_type='OUTBOUND_SHIPMENT_SHIPPED'
                  AND actor_type='TENANT_MEMBER' AND actor_membership_id=?
                  AND JSON_UNQUOTE(JSON_EXTRACT(change_payload,'$.requestId'))=?
                """, fixture.tenantId(), shipmentId, actorMembershipId, shippingRequestId.toString()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND subject_public_id=? AND event_type='PICKING_CONFIRMED'
                  AND actor_type='TENANT_MEMBER' AND actor_membership_id=?
                  AND JSON_UNQUOTE(JSON_EXTRACT(change_payload,'$.requestId'))=?
                """, fixture.tenantId(), taskId, actorMembershipId, pickingRequestId.toString()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND event_type='PACKING_SESSION_COMPLETED'
                  AND subject_public_id=? AND actor_membership_id=? AND actor_type='TENANT_MEMBER'
                """, fixture.tenantId(), sessionId, actorMembershipId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND event_type='OUTBOUND_SHIPMENT_RELEASED'
                  AND subject_public_id=? AND actor_membership_id=? AND actor_type='TENANT_MEMBER'
                """, fixture.tenantId(), shipmentId, actorMembershipId));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND event_type IN (
                  'OUTBOUND_SHIPMENT_CREATED','OUTBOUND_SHIPMENT_LINE_ADDED',
                  'OUTBOUND_SHIPMENT_ALLOCATED','OUTBOUND_SHIPMENT_RELEASED',
                  'PICKING_TASK_CREATED','PICKING_TASK_ASSIGNED','PICKING_TASK_STARTED',
                  'PICKING_CONFIRMED','PACKING_SESSION_CREATED','PACKING_SESSION_READY',
                  'PACKING_SESSION_ASSIGNED','PACKING_SESSION_STARTED','PACKING_PACKAGE_CREATED',
                  'PACKING_PACKAGE_LINE_ADDED','PACKING_PACKAGE_SEALED','PACKING_SESSION_COMPLETED',
                  'OUTBOUND_SHIPMENT_SHIPPED'
                ) AND operation_ref IS NULL
                """, fixture.tenantId()));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM (
                  SELECT operation_ref FROM tenant_audit_events
                  WHERE tenant_id=? AND event_type IN (
                    'OUTBOUND_SHIPMENT_CREATED','OUTBOUND_SHIPMENT_LINE_ADDED',
                    'OUTBOUND_SHIPMENT_ALLOCATED','OUTBOUND_SHIPMENT_RELEASED',
                    'PICKING_TASK_CREATED','PICKING_TASK_ASSIGNED','PICKING_TASK_STARTED',
                    'PICKING_CONFIRMED','PACKING_SESSION_CREATED','PACKING_SESSION_READY',
                    'PACKING_SESSION_ASSIGNED','PACKING_SESSION_STARTED','PACKING_PACKAGE_CREATED',
                    'PACKING_PACKAGE_LINE_ADDED','PACKING_PACKAGE_SEALED','PACKING_SESSION_COMPLETED',
                    'OUTBOUND_SHIPMENT_SHIPPED'
                  )
                  GROUP BY operation_ref HAVING COUNT(*) > 1
                ) duplicate_refs
                """, fixture.tenantId()));
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

    @Test
    void packingSessionDetailIsTenantScopedInMySql() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0000"), List.of()))));
        shipmentService.release(fixture.principal(), shipmentId);

        String taskId = (String) pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-" + UUID.randomUUID().toString().substring(0, 8), "NORMAL", null))
                .get("public_id");
        pickingService.assign(fixture.principal(), taskId,
                new WmsPickingManagementService.AssignPickingCommand(fixture.membershipPublicId()));
        pickingService.start(fixture.principal(), taskId);
        String staging = UUID.randomUUID().toString();
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class, fixture.tenantId(), fixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Tenant Read Boundary Staging','STAGING','ACTIVE')
                """, staging, fixture.tenantId(), warehouseId, "STG_" + UUID.randomUUID().toString().substring(0, 8));
        pickingService.confirm(fixture.principal(), taskId, 1,
                new WmsPickingManagementService.ConfirmPickingCommand(
                        UUID.randomUUID(), new BigDecimal("5.0000"), staging, List.of(), null));
        String sessionId = (String) packingService.create(fixture.principal(),
                new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                        "PACK-" + UUID.randomUUID().toString().substring(0, 8), null)).get("public_id");

        Fixture otherTenant = fixture("NONE");

        assertEquals(1, count(
                "SELECT COUNT(*) FROM outbound_shipments WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), shipmentId));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM outbound_shipments WHERE tenant_id=? AND public_id=?",
                otherTenant.tenantId(), shipmentId));
        assertThrows(WmsResourceNotFoundException.class,
                () -> shipmentService.get(otherTenant.principal(), shipmentId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM outbound_shipments WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), shipmentId));

        assertEquals(1, count(
                "SELECT COUNT(*) FROM picking_tasks WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), taskId));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM picking_tasks WHERE tenant_id=? AND public_id=?",
                otherTenant.tenantId(), taskId));
        assertThrows(WmsResourceNotFoundException.class,
                () -> pickingService.get(otherTenant.principal(), taskId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM picking_tasks WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), taskId));
        assertEquals(1, count("SELECT COUNT(*) FROM packing_sessions WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), sessionId));
        assertEquals(0, count("SELECT COUNT(*) FROM packing_sessions WHERE tenant_id=? AND public_id=?",
                otherTenant.tenantId(), sessionId));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> packingService.get(otherTenant.principal(), sessionId));
        assertEquals(1, count("SELECT COUNT(*) FROM packing_sessions WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), sessionId));
    }

    @Test
    void outboundShipmentAndPickingResourcesAreTenantIsolatedAcrossWorkflowServices() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0000"), List.of()))));
        shipmentService.release(fixture.principal(), shipmentId);

        String taskId = (String) pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-" + UUID.randomUUID().toString().substring(0, 8), "NORMAL", null))
                .get("public_id");

        Fixture otherTenant = fixture("NONE");
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> shipmentService.get(otherTenant.principal(), shipmentId));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> shippingService.get(otherTenant.principal(), shipmentId));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> pickingService.get(otherTenant.principal(), taskId));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> pickingService.create(otherTenant.principal(),
                        new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                                "CROSS-" + UUID.randomUUID().toString().substring(0, 8), "NORMAL", null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> packingService.create(otherTenant.principal(),
                        new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                                "CROSS-PACK-" + UUID.randomUUID().toString().substring(0, 8), null)));

        assertEquals("PICKING", shipmentStatus(fixture, shipmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(new BigDecimal("5.0000"), reserved(fixture));
    }

    @Test
    void outboundNestedLineAndLocationReferencesCannotCrossTenantBoundaries() {
        String ownShipmentId = createShipment(fixture);
        String ownLineId = addLine(fixture, ownShipmentId, "5.0000");

        Fixture otherTenant = fixture("NONE");
        receive(otherTenant, "5.0000", List.of());
        String foreignShipmentId = createShipment(otherTenant);
        String foreignLineId = addLine(otherTenant, foreignShipmentId, "5.0000");

        WmsOutboundShipmentService.AllocationCommand ownLocationAllocation =
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, BigDecimal.ONE, List.of())));

        assertThrows(WmsResourceNotFoundException.class,
                () -> shipmentService.allocate(fixture.principal(), ownShipmentId, foreignLineId,
                        ownLocationAllocation));

        WmsOutboundShipmentService.AllocationCommand foreignLocationAllocation =
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                otherTenant.receivingLocationPublicId(), null, BigDecimal.ONE, List.of())));

        assertThrows(WmsResourceNotFoundException.class,
                () -> shipmentService.allocate(fixture.principal(), ownShipmentId, ownLineId,
                        foreignLocationAllocation));

        assertEquals(BigDecimal.ZERO.setScale(4), reserved(fixture));
        assertEquals(BigDecimal.ZERO.setScale(4), reserved(otherTenant));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM outbound_shipment_allocations
                WHERE tenant_id = ? AND outbound_shipment_line_id = (
                  SELECT id FROM outbound_shipment_lines WHERE tenant_id = ? AND public_id = ?
                )
                """, fixture.tenantId(), fixture.tenantId(), ownLineId));
        assertEquals("DRAFT", shipmentStatus(fixture, ownShipmentId));
        assertEquals("DRAFT", shipmentStatus(otherTenant, foreignShipmentId));
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


    @Test
    void duplicatePickingAndPackingRequestIdsCannotRepeatWorkflowEffects() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0000"), List.of()))));
        shipmentService.release(fixture.principal(), shipmentId);
        String taskId = (String) pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-REPLAY-" + UUID.randomUUID().toString().substring(0, 8), "NORMAL", null))
                .get("public_id");
        pickingService.assign(fixture.principal(), taskId,
                new WmsPickingManagementService.AssignPickingCommand(fixture.membershipPublicId()));
        pickingService.start(fixture.principal(), taskId);

        String staging = UUID.randomUUID().toString();
        long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?", Long.class,
                fixture.tenantId(), fixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Replay Staging','STAGING','ACTIVE')
                """, staging, fixture.tenantId(), warehouseId, "STG_REPLAY_" + UUID.randomUUID().toString().substring(0, 6));

        UUID pickingRequestId = UUID.randomUUID();
        WmsPickingManagementService.ConfirmPickingCommand partialPick =
                new WmsPickingManagementService.ConfirmPickingCommand(
                        pickingRequestId, new BigDecimal("2.0000"), staging, List.of(), "Partial pick replay");
        pickingService.confirm(fixture.principal(), taskId, 1, partialPick);
        int ledgerAfterFirstPick = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", fixture.tenantId());
        assertThrows(IllegalStateException.class, () -> pickingService.confirm(fixture.principal(), taskId, 1, partialPick));
        assertEquals(ledgerAfterFirstPick, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", fixture.tenantId()));
        assertEquals(new BigDecimal("2.0000"), jdbc.queryForObject("""
                SELECT picked_qty FROM picking_task_lines
                WHERE tenant_id=? AND picking_task_id=(SELECT id FROM picking_tasks WHERE tenant_id=? AND public_id=?)
                """, BigDecimal.class, fixture.tenantId(), fixture.tenantId(), taskId));

        pickingService.confirm(fixture.principal(), taskId, 1,
                new WmsPickingManagementService.ConfirmPickingCommand(
                        UUID.randomUUID(), new BigDecimal("3.0000"), staging, List.of(), "Finish pick"));

        String sessionId = (String) packingService.create(fixture.principal(),
                new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                        "PACK-REPLAY-" + UUID.randomUUID().toString().substring(0, 8), null)).get("public_id");
        packingService.ready(fixture.principal(), sessionId);
        packingService.assign(fixture.principal(), sessionId,
                new WmsPackingManagementService.AssignSessionCommand(fixture.membershipPublicId()));
        packingService.start(fixture.principal(), sessionId);
        String packageId = (String) packingService.createPackage(fixture.principal(), sessionId,
                new WmsPackingManagementService.CreatePackageCommand(
                        "BOX-REPLAY-" + UUID.randomUUID().toString().substring(0, 8), "CARTON",
                        null, null, null, null, null)).get("public_id");

        UUID packingRequestId = UUID.randomUUID();
        WmsPackingManagementService.AddPackageLineCommand packageLine =
                new WmsPackingManagementService.AddPackageLineCommand(
                        packingRequestId, 1, BigDecimal.ONE, List.of(), "Packing replay");
        packingService.addLine(fixture.principal(), sessionId, packageId, packageLine);
        int packageLinesAfterFirst = count("""
                SELECT COUNT(*) FROM packing_package_lines
                WHERE tenant_id=? AND packing_session_id=(
                  SELECT id FROM packing_sessions WHERE tenant_id=? AND public_id=?
                )
                """, fixture.tenantId(), fixture.tenantId(), sessionId);
        assertThrows(IllegalStateException.class,
                () -> packingService.addLine(fixture.principal(), sessionId, packageId, packageLine));
        assertEquals(packageLinesAfterFirst, count("""
                SELECT COUNT(*) FROM packing_package_lines
                WHERE tenant_id=? AND packing_session_id=(
                  SELECT id FROM packing_sessions WHERE tenant_id=? AND public_id=?
                )
                """, fixture.tenantId(), fixture.tenantId(), sessionId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM packing_confirmations
                WHERE tenant_id=? AND request_id=?
                """, fixture.tenantId(), packingRequestId.toString()));
    }

    @Test
    void shippingAuditFailureRollsBackFinalIssueAndShipmentTransition() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0000"), List.of()))));
        shipmentService.release(fixture.principal(), shipmentId);
        String taskId = (String) pickingService.create(fixture.principal(),
                new WmsPickingManagementService.CreatePickingCommand(shipmentId,
                        "PICK-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 8), "NORMAL", null))
                .get("public_id");
        pickingService.assign(fixture.principal(), taskId,
                new WmsPickingManagementService.AssignPickingCommand(fixture.membershipPublicId()));
        pickingService.start(fixture.principal(), taskId);
        String staging = UUID.randomUUID().toString();
        long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?", Long.class,
                fixture.tenantId(), fixture.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,
                  location_name,location_type,status)
                VALUES(?,?,?,?,'Rollback Staging','STAGING','ACTIVE')
                """, staging, fixture.tenantId(), warehouseId, "STG_ROLLBACK_" + UUID.randomUUID().toString().substring(0, 6));
        pickingService.confirm(fixture.principal(), taskId, 1,
                new WmsPickingManagementService.ConfirmPickingCommand(
                        UUID.randomUUID(), new BigDecimal("5.0000"), staging, List.of(), null));

        String sessionId = (String) packingService.create(fixture.principal(),
                new WmsPackingManagementService.CreateSessionCommand(shipmentId,
                        "PACK-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 8), null)).get("public_id");
        packingService.ready(fixture.principal(), sessionId);
        packingService.assign(fixture.principal(), sessionId,
                new WmsPackingManagementService.AssignSessionCommand(fixture.membershipPublicId()));
        packingService.start(fixture.principal(), sessionId);
        String packageId = (String) packingService.createPackage(fixture.principal(), sessionId,
                new WmsPackingManagementService.CreatePackageCommand(
                        "BOX-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 8), "CARTON",
                        null, null, null, null, null)).get("public_id");
        packingService.addLine(fixture.principal(), sessionId, packageId,
                new WmsPackingManagementService.AddPackageLineCommand(
                        UUID.randomUUID(), 1, new BigDecimal("5.0000"), List.of(), null));
        packingService.sealPackage(fixture.principal(), sessionId, packageId);
        packingService.complete(fixture.principal(), sessionId);

        int ledgerBeforeShipping = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", fixture.tenantId());
        jdbc.execute("ALTER TABLE tenant_audit_events "
                + "ADD CONSTRAINT chk_test_block_outbound_ship_audit "
                + "CHECK (event_type <> 'OUTBOUND_SHIPMENT_SHIPPED' OR subject_public_id <> '"
                + shipmentId + "')");
        try {
            assertThrows(org.springframework.jdbc.UncategorizedSQLException.class, () -> shippingService.ship(fixture.principal(), shipmentId,
                    new WmsShippingManagementService.ShipCommand(UUID.randomUUID(), null, "ROLLBACK-TRACK", null)));
        } finally {
            jdbc.execute("ALTER TABLE tenant_audit_events DROP CHECK chk_test_block_outbound_ship_audit");
        }

        assertEquals("PACKED", shipmentStatus(fixture, shipmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(ledgerBeforeShipping, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", fixture.tenantId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='ISSUE'",
                fixture.tenantId()));
    }

    @Test
    void interWarehouseTransferDispatchesAndReceivesPartialGoodDamagedAndShortQuantities() {
        Destination destination = destination(fixture);
        String transferId = createTransfer(fixture, destination, "5.0000", List.of());
        String allocationId = allocationPublicId(fixture, transferId);
        transferService.release(fixture.principal(), transferId, UUID.randomUUID());

        transferService.dispatch(fixture.principal(), transferId,
                new WmsInterWarehouseTransferService.DispatchCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.DispatchAllocation(
                                allocationId, new BigDecimal("4.0000"), List.of())), null));
        assertEquals(new BigDecimal("1.0000"), balanceAt(fixture, fixture.warehousePublicId(),
                fixture.receivingLocationPublicId(), "AVAILABLE"));
        assertEquals(BigDecimal.ZERO.setScale(4), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "AVAILABLE"));

        transferService.receive(fixture.principal(), transferId,
                new WmsInterWarehouseTransferService.ReceiveCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.ReceiveAllocation(allocationId,
                                new BigDecimal("2.0000"), new BigDecimal("1.0000"),
                                new BigDecimal("1.0000"), List.of(), List.of(), List.of())), null));
        assertEquals(new BigDecimal("2.0000"), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "AVAILABLE"));
        assertEquals(new BigDecimal("1.0000"), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "DAMAGED"));
        assertEquals("IN_TRANSIT", transferStatus(fixture, transferId));

        transferService.dispatch(fixture.principal(), transferId,
                new WmsInterWarehouseTransferService.DispatchCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.DispatchAllocation(
                                allocationId, new BigDecimal("1.0000"), List.of())), null));
        transferService.receive(fixture.principal(), transferId,
                new WmsInterWarehouseTransferService.ReceiveCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.ReceiveAllocation(allocationId,
                                new BigDecimal("1.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                                List.of(), List.of(), List.of())), null));
        assertEquals("RECEIVED", transferStatus(fixture, transferId));
        assertEquals(new BigDecimal("3.0000"), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "AVAILABLE"));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER_DISPATCH'",
                fixture.tenantId()));
        assertEquals(3, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER_RECEIPT'",
                fixture.tenantId()));
    }

    @Test
    void duplicateDispatchAndReceiptRequestIdsCannotMoveTransferStockTwice() {
        Destination destination = destination(fixture);
        String transferId = createTransfer(fixture, destination, "5.0000", List.of());
        String allocationId = allocationPublicId(fixture, transferId);
        transferService.release(fixture.principal(), transferId, UUID.randomUUID());

        UUID dispatchRequestId = UUID.randomUUID();
        WmsInterWarehouseTransferService.DispatchCommand dispatch =
                new WmsInterWarehouseTransferService.DispatchCommand(dispatchRequestId,
                        List.of(new WmsInterWarehouseTransferService.DispatchAllocation(
                                allocationId, new BigDecimal("4.0000"), List.of())), null);
        transferService.dispatch(fixture.principal(), transferId, dispatch);
        assertEquals(new BigDecimal("1.0000"), balanceAt(fixture, fixture.warehousePublicId(),
                fixture.receivingLocationPublicId(), "AVAILABLE"));
        assertThrows(IllegalStateException.class,
                () -> transferService.dispatch(fixture.principal(), transferId, dispatch));
        assertEquals(new BigDecimal("1.0000"), balanceAt(fixture, fixture.warehousePublicId(),
                fixture.receivingLocationPublicId(), "AVAILABLE"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER_DISPATCH'",
                fixture.tenantId()));

        UUID receiveRequestId = UUID.randomUUID();
        WmsInterWarehouseTransferService.ReceiveCommand receive =
                new WmsInterWarehouseTransferService.ReceiveCommand(receiveRequestId,
                        List.of(new WmsInterWarehouseTransferService.ReceiveAllocation(allocationId,
                                new BigDecimal("2.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                                List.of(), List.of(), List.of())), null);
        transferService.receive(fixture.principal(), transferId, receive);
        assertEquals(new BigDecimal("2.0000"), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "AVAILABLE"));
        assertThrows(IllegalStateException.class,
                () -> transferService.receive(fixture.principal(), transferId, receive));
        assertEquals(new BigDecimal("2.0000"), balanceAt(fixture, destination.warehousePublicId(),
                destination.locationPublicId(), "AVAILABLE"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER_RECEIPT'",
                fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND subject_public_id=? AND event_type='WMS_STOCK_TRANSFER_UPDATED'
                  AND operation_ref=? AND actor_type='TENANT_MEMBER'
                """, fixture.tenantId(), transferId, dispatchRequestId.toString()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND subject_public_id=? AND event_type='WMS_STOCK_TRANSFER_UPDATED'
                  AND operation_ref=? AND actor_type='TENANT_MEMBER'
                """, fixture.tenantId(), transferId, receiveRequestId.toString()));
        assertEquals("IN_TRANSIT", transferStatus(fixture, transferId));
    }

    @Test
    void serialTransferPreservesIdentityAcrossTransitAndReconcilesDamagedAndLostSerials() {
        Fixture serialFixture = fixture("SERIAL");
        List<String> serialNumbers = List.of("TR-" + UUID.randomUUID(), "TR-" + UUID.randomUUID(), "TR-" + UUID.randomUUID());
        receive(serialFixture, "3.0000", serialNumbers);
        List<String> serialIds = jdbc.queryForList("SELECT public_id FROM inventory_serials WHERE tenant_id=? ORDER BY serial_number",
                String.class, serialFixture.tenantId());
        Destination destination = destination(serialFixture);
        String transferId = createTransfer(serialFixture, destination, "3.0000", serialIds);
        String allocationId = allocationPublicId(serialFixture, transferId);
        transferService.release(serialFixture.principal(), transferId, UUID.randomUUID());
        transferService.dispatch(serialFixture.principal(), transferId,
                new WmsInterWarehouseTransferService.DispatchCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.DispatchAllocation(
                                allocationId, new BigDecimal("3.0000"), serialIds)), null));

        assertEquals(3, count("SELECT COUNT(*) FROM inventory_serials WHERE tenant_id=? AND status='IN_TRANSIT' AND current_warehouse_id IS NULL AND current_location_id IS NULL",
                serialFixture.tenantId()));
        transferService.receive(serialFixture.principal(), transferId,
                new WmsInterWarehouseTransferService.ReceiveCommand(UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.ReceiveAllocation(allocationId,
                                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                                List.of(serialIds.get(0)), List.of(serialIds.get(1)), List.of(serialIds.get(2)))), null));

        assertEquals("RECEIVED", transferStatus(serialFixture, transferId));
        assertEquals("AVAILABLE", serialStatus(serialFixture, serialIds.get(0)));
        assertEquals("DAMAGED", serialStatus(serialFixture, serialIds.get(1)));
        assertEquals("LOST", serialStatus(serialFixture, serialIds.get(2)));
        assertEquals(destination.warehousePublicId(), serialWarehouse(serialFixture, serialIds.get(0)));
        assertEquals(destination.locationPublicId(), serialLocation(serialFixture, serialIds.get(0)));
        assertNull(serialWarehouse(serialFixture, serialIds.get(2)));
    }

    @Test
    void interWarehouseTransfersAreIsolatedAcrossTenantsForReadAndCreation() {
        Destination destination = destination(fixture);
        String transferId = createTransfer(fixture, destination, "5.0000", List.of());

        Fixture otherTenant = fixture("NONE");

        assertTrue(transferService.list(fixture.principal()).stream()
                .anyMatch(row -> transferId.equals(row.get("public_id"))));
        assertFalse(transferService.list(otherTenant.principal()).stream()
                .anyMatch(row -> transferId.equals(row.get("public_id"))));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> transferService.get(otherTenant.principal(), transferId));

        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class, () ->
                transferService.create(
                        otherTenant.principal(),
                        new WmsInterWarehouseTransferService.TransferCommand(
                                UUID.randomUUID(), fixture.warehousePublicId(), destination.warehousePublicId(),
                                "CROSS-" + UUID.randomUUID().toString().substring(0, 8), null, "STANDARD",
                                null, null,
                                List.of(
                                        new WmsInterWarehouseTransferService.LineCommand(
                                                otherTenant.itemPublicId(), new BigDecimal("1.0000"), null,
                                                List.of(
                                                        new WmsInterWarehouseTransferService.AllocationCommand(
                                                                otherTenant.receivingLocationPublicId(),
                                                                destination.locationPublicId(), null,
                                                                new BigDecimal("1.0000"), List.of())
                                                )
                                        )
                                )
                        )
                )
        );
        assertEquals(0, count("""
                SELECT COUNT(*) FROM stock_transfers
                WHERE tenant_id=? AND document_number LIKE 'CROSS-%'
                """, otherTenant.tenantId()));

        jdbc.update("""
                DELETE rp FROM role_permissions rp
                JOIN permissions p ON p.id=rp.permission_id
                JOIN membership_roles mr ON mr.role_id=rp.role_id
                WHERE mr.tenant_id=? AND mr.membership_id=? AND p.permission_code='WMS_TRANSFER_DISPATCH'
                """, fixture.tenantId(), fixture.principal().membershipId());
        assertThrows(AccessDeniedException.class, () -> transferService.dispatch(
                fixture.principal(), transferId,
                new WmsInterWarehouseTransferService.DispatchCommand(
                        UUID.randomUUID(),
                        List.of(new WmsInterWarehouseTransferService.DispatchAllocation(
                                allocationPublicId(fixture, transferId), BigDecimal.ONE, List.of())),
                        null)));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals("DRAFT", transferStatus(fixture, transferId));
    }

    private String createTransfer(Fixture f, Destination d, String qty, List<String> serialIds) {
        String id = (String) transferService.create(f.principal(),
                new WmsInterWarehouseTransferService.TransferCommand(UUID.randomUUID(),
                        f.warehousePublicId(), d.warehousePublicId(), "TR-" + UUID.randomUUID().toString().substring(0,12),
                        null, "STANDARD", null, null,
                        List.of(new WmsInterWarehouseTransferService.LineCommand(
                                f.itemPublicId(), new BigDecimal(qty), null,
                                List.of(new WmsInterWarehouseTransferService.AllocationCommand(
                                        f.receivingLocationPublicId(), d.locationPublicId(), null,
                                        new BigDecimal(qty), serialIds)))))).get("public_id");
        return id;
    }

    private Destination destination(Fixture f) {
        String warehouse = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO warehouses(public_id,tenant_id,warehouse_code,warehouse_name,status) VALUES(?,?,?,?,'ACTIVE')",
                warehouse, f.tenantId(), "DST_" + UUID.randomUUID().toString().substring(0,8), "Transfer Destination");
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class, f.tenantId(), warehouse);
        String location = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations(public_id,tenant_id,warehouse_id,location_code,location_name,location_type,status)
                VALUES(?,?,?,?,'Destination Storage','STORAGE','ACTIVE')
                """, location, f.tenantId(), warehouseId, "DST_" + UUID.randomUUID().toString().substring(0,8));
        return new Destination(warehouse, location);
    }

    private String allocationPublicId(Fixture f, String transferId) {
        return jdbc.queryForObject("""
                SELECT a.public_id FROM stock_transfer_allocations a
                JOIN stock_transfer_lines l ON l.id=a.stock_transfer_line_id AND l.tenant_id=a.tenant_id
                JOIN stock_transfers t ON t.id=l.stock_transfer_id AND t.tenant_id=l.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """, String.class, f.tenantId(), transferId);
    }

    private String transferStatus(Fixture f, String transferId) {
        return jdbc.queryForObject("SELECT status FROM stock_transfers WHERE tenant_id=? AND public_id=?",
                String.class, f.tenantId(), transferId);
    }

    private BigDecimal balanceAt(Fixture f,String warehousePublicId,String locationPublicId,String status) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.on_hand_qty),0.0000)
                FROM inventory_balances b
                JOIN warehouses w ON w.id=b.warehouse_id AND w.tenant_id=b.tenant_id
                JOIN warehouse_locations l ON l.id=b.location_id AND l.warehouse_id=b.warehouse_id AND l.tenant_id=b.tenant_id
                JOIN items i ON i.id=b.item_id AND i.tenant_id=b.tenant_id
                WHERE b.tenant_id=? AND w.public_id=? AND l.public_id=? AND i.public_id=? AND b.stock_status=?
                """, BigDecimal.class, f.tenantId(), warehousePublicId, locationPublicId, f.itemPublicId(), status);
    }

    private String serialStatus(Fixture f,String serialPublicId) {
        return jdbc.queryForObject("SELECT status FROM inventory_serials WHERE tenant_id=? AND public_id=?",
                String.class, f.tenantId(), serialPublicId);
    }

    private String serialWarehouse(Fixture f,String serialPublicId) {
        return jdbc.queryForObject("""
                SELECT w.public_id FROM inventory_serials s LEFT JOIN warehouses w
                  ON w.id=s.current_warehouse_id AND w.tenant_id=s.tenant_id
                WHERE s.tenant_id=? AND s.public_id=?
                """, String.class, f.tenantId(), serialPublicId);
    }

    private String serialLocation(Fixture f,String serialPublicId) {
        return jdbc.queryForObject("""
                SELECT l.public_id FROM inventory_serials s LEFT JOIN warehouse_locations l
                  ON l.id=s.current_location_id AND l.warehouse_id=s.current_warehouse_id AND l.tenant_id=s.tenant_id
                WHERE s.tenant_id=? AND s.public_id=?
                """, String.class, f.tenantId(), serialPublicId);
    }

    private record Destination(String warehousePublicId,String locationPublicId) {}

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
                  'WMS_INVENTORY_RESERVE',
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
                  'WMS_SHIPPING_READ', 'WMS_SHIPPING_SHIP',
                  'WMS_TRANSFER_READ', 'WMS_TRANSFER_CREATE', 'WMS_TRANSFER_RELEASE',
                  'WMS_TRANSFER_DISPATCH', 'WMS_TRANSFER_RECEIVE'
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

    @Test
    void outboundShipmentRejectsForeignTenantWarehouseCustomerAndItemReferencesWithoutSideEffects() {
        Fixture otherTenant = fixture("NONE");
        String ownShipmentId = createShipment(fixture);
        assertNotNull(ownShipmentId);
        int shipmentCountBefore = count("SELECT COUNT(*) FROM outbound_shipments WHERE tenant_id=?", fixture.tenantId());
        int lineCountBefore = count("SELECT COUNT(*) FROM outbound_shipment_lines WHERE tenant_id=?", fixture.tenantId());
        int auditCountBefore = count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id=?", fixture.tenantId());

        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> shipmentService.create(fixture.principal(),
                        new WmsOutboundShipmentService.CreateShipmentCommand(
                                otherTenant.warehousePublicId(), fixture.customerPublicId(), null,
                                "CROSS-WH-" + UUID.randomUUID(), null, "SALES", null, null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> shipmentService.create(fixture.principal(),
                        new WmsOutboundShipmentService.CreateShipmentCommand(
                                fixture.warehousePublicId(), otherTenant.customerPublicId(), null,
                                "CROSS-CUSTOMER-" + UUID.randomUUID(), null, "SALES", null, null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> shipmentService.addLine(fixture.principal(), ownShipmentId,
                        new WmsOutboundShipmentService.ShipmentLineCommand(
                                otherTenant.itemPublicId(), new BigDecimal("1.0000"), null)));

        assertEquals(shipmentCountBefore,
                count("SELECT COUNT(*) FROM outbound_shipments WHERE tenant_id=?", fixture.tenantId()));
        assertEquals(lineCountBefore,
                count("SELECT COUNT(*) FROM outbound_shipment_lines WHERE tenant_id=?", fixture.tenantId()));
        assertEquals(auditCountBefore,
                count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id=?", fixture.tenantId()));
        assertEquals("DRAFT", shipmentStatus(fixture, ownShipmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(BigDecimal.ZERO.setScale(4), reserved(fixture));

        String validLineId = addLine(fixture, ownShipmentId, "1.0000");
        assertNotNull(validLineId);
        assertEquals(1, count("SELECT COUNT(*) FROM outbound_shipment_lines WHERE tenant_id=? AND public_id=?",
                fixture.tenantId(), validLineId));
    }

    @Test
    void rejectedPartialReleasePreservesShipmentStateAndReservation() {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("3.0000"), List.of()))));

        assertEquals("DRAFT", shipmentStatus(fixture, shipmentId));
        assertEquals(new BigDecimal("3.0000"), reserved(fixture));
        assertEquals(new BigDecimal("2.0000"), available(fixture));

        assertThrows(IllegalStateException.class, () -> shipmentService.release(fixture.principal(), shipmentId));

        assertEquals("DRAFT", shipmentStatus(fixture, shipmentId));
        assertEquals(new BigDecimal("3.0000"), reserved(fixture));
        assertEquals(new BigDecimal("2.0000"), available(fixture));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                  AND event_type = 'OUTBOUND_SHIPMENT_RELEASED'
                """, fixture.tenantId(), shipmentId));
    }

    @Test
    void concurrentReleaseRequestsAllowOnlyOneShipmentStateTransition() throws Exception {
        String shipmentId = createShipment(fixture);
        String lineId = addLine(fixture, shipmentId, "5.0000");
        shipmentService.allocate(fixture.principal(), shipmentId, lineId,
                new WmsOutboundShipmentService.AllocationCommand(List.of(
                        new WmsOutboundShipmentService.AllocationLine(
                                fixture.receivingLocationPublicId(), null, new BigDecimal("5.0000"), List.of()))));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent release barrier timed out");
                }
                try {
                    shipmentService.release(fixture.principal(), shipmentId);
                    return true;
                } catch (IllegalStateException rejected) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both independent requests must reach the release barrier");
            start.countDown();

            int successfulTransitions = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successfulTransitions);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals("RELEASED", shipmentStatus(fixture, shipmentId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                  AND event_type = 'OUTBOUND_SHIPMENT_RELEASED'
                """, fixture.tenantId(), shipmentId));
    }

    private record Fixture(long tenantId, String warehousePublicId, String receivingLocationPublicId,
                           String supplierPublicId, String customerPublicId, String itemPublicId,
                           String membershipPublicId, WmsAuthenticatedPrincipal principal) {}
}

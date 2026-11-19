package com.zirocraft.zirologistics.wms.reporting.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only, tenant-scoped operational summaries for the WMS runtime.
 * Table names are fixed allowlisted constants; every query is constrained by tenant_id.
 */
@Service
@Profile("wms")
public class WmsOperationalReportingService {
    private static final List<WorkflowSource> WORKFLOWS = List.of(
            new WorkflowSource("inbound_receipts", "inboundReceipts"),
            new WorkflowSource("outbound_shipments", "outboundShipments"),
            new WorkflowSource("stock_transfers", "interWarehouseTransfers"),
            new WorkflowSource("replenishment_tasks", "replenishmentTasks"),
            new WorkflowSource("stock_counts", "stockCounts"),
            new WorkflowSource("inventory_adjustments", "inventoryAdjustments")
    );

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;

    public WmsOperationalReportingService(WmsMembershipAccessService accessService, JdbcTemplate jdbc) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> operationalSummary(WmsAuthenticatedPrincipal principal) {
        WmsMembershipAccess access = resolve(principal);
        long tenantId = access.tenantId();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", java.sql.Timestamp.class));
        result.put("inventoryPosition", jdbc.queryForMap("""
                SELECT COALESCE(SUM(on_hand_qty), 0.0000) AS onHandQty,
                       COALESCE(SUM(reserved_qty), 0.0000) AS reservedQty,
                       COALESCE(SUM(CASE WHEN stock_status = 'AVAILABLE' THEN available_qty ELSE 0 END), 0.0000) AS availableQty,
                       COUNT(DISTINCT item_id) AS distinctItemCount,
                       COUNT(DISTINCT warehouse_id) AS warehouseCount
                FROM inventory_balances
                WHERE tenant_id = ?
                """, tenantId));

        Map<String, Object> workflows = new LinkedHashMap<>();
        for (WorkflowSource source : WORKFLOWS) {
            workflows.put(source.responseKey(), jdbc.queryForList(
                    "SELECT status, COUNT(*) AS documentCount FROM " + source.tableName()
                            + " WHERE tenant_id = ? GROUP BY status ORDER BY status",
                    tenantId));
        }
        result.put("workflowDocumentsByStatus", workflows);
        return result;
    }

    private WmsMembershipAccess resolve(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess access = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(access.userPublicId(), principal.userPublicId())
                || !Objects.equals(access.tenantId(), principal.tenantId())
                || !Objects.equals(access.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(access.membershipId(), principal.membershipId())
                || !Objects.equals(access.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!access.hasPermission("WMS_OPERATIONAL_REPORT_READ")) {
            throw new AccessDeniedException("Required operational reporting permission is missing");
        }
        return access;
    }

    private record WorkflowSource(String tableName, String responseKey) {}
}

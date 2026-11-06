package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface InventoryStockRepository extends JpaRepository<InventoryStockEntity, Long> {
    // Find stock for one SKU, bin, and inventory status.
    Optional<InventoryStockEntity> findByItemSkuAndBinBinCodeAndStatus(String sku, String binCode, String status);

    /**
     * A low-stock alert represents one SKU in one warehouse, not one stock row/bin.
     * Only AVAILABLE quantities are counted; damaged/reserved stock is not available
     * to satisfy the warehouse's replenishment threshold.
     */
    @Query(
            value = """
                    SELECT i.sku AS itemSku,
                           i.name AS itemName,
                           w.name AS warehouseName,
                           SUM(s.quantity) AS availableQuantity,
                           i.minStockLevel AS minStockLevel
                    FROM InventoryStockEntity s
                    JOIN s.item i
                    JOIN s.bin b
                    JOIN b.warehouse w
                    WHERE s.status = 'AVAILABLE'
                      AND i.isDeleted = false
                      AND b.isDeleted = false
                      AND w.isDeleted = false
                    GROUP BY i.id, i.sku, i.name, i.minStockLevel, w.id, w.name
                    HAVING SUM(s.quantity) < i.minStockLevel
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM (
                        SELECT i.id, w.id
                        FROM InventoryStockEntity s
                        JOIN s.item i
                        JOIN s.bin b
                        JOIN b.warehouse w
                        WHERE s.status = 'AVAILABLE'
                          AND i.isDeleted = false
                          AND b.isDeleted = false
                          AND w.isDeleted = false
                        GROUP BY i.id, w.id, i.minStockLevel
                        HAVING SUM(s.quantity) < i.minStockLevel
                    ) low_stock_groups
                    """
    )
    Page<LowStockAlertProjection> findLowStockByWarehouse(Pageable pageable);
}

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
     * One alert per SKU and warehouse. The result quantity is the sum of AVAILABLE
     * stock across every active bin belonging to that warehouse.
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
                    GROUP BY i.id, i.sku, i.name, i.minStockLevel, w.id, w.name, w.code
                    HAVING SUM(s.quantity) < i.minStockLevel
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT CONCAT(i.sku, '|', w.code))
                    FROM InventoryStockEntity s
                    JOIN s.item i
                    JOIN s.bin b
                    JOIN b.warehouse w
                    WHERE s.status = 'AVAILABLE'
                      AND i.isDeleted = false
                      AND b.isDeleted = false
                      AND w.isDeleted = false
                      AND (
                          SELECT COALESCE(SUM(s2.quantity), 0)
                          FROM InventoryStockEntity s2
                          JOIN s2.bin b2
                          WHERE s2.item = i
                            AND b2.warehouse = w
                            AND s2.status = 'AVAILABLE'
                            AND b2.isDeleted = false
                      ) < i.minStockLevel
                    """
    )
    Page<LowStockAlertProjection> findLowStockByWarehouse(Pageable pageable);
}

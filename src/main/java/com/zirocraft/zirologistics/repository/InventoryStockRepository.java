package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface InventoryStockRepository extends JpaRepository<InventoryStockEntity, Long> {
    // Cari stok barang di rak tertentu dengan status tertentu
    Optional<InventoryStockEntity> findByItemSkuAndBinBinCodeAndStatus(String sku, String binCode, String status);

    @Query("SELECT s FROM InventoryStockEntity s WHERE s.quantity < s.item.minStockLevel")
    Page<InventoryStockEntity> findLowStock(Pageable pageable);
}
package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InventoryStockRepository extends JpaRepository<InventoryStockEntity, Long> {
    // Cari stok barang di rak tertentu dengan status tertentu
    Optional<InventoryStockEntity> findByItemSkuAndBinBinCodeAndStatus(String sku, String binCode, String status);
}
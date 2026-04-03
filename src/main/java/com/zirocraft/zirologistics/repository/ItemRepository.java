package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.ItemEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<ItemEntity, Long> {
    Optional<ItemEntity> findBySku(String sku);
    Optional<ItemEntity> findByPublicId(String publicId);

    // GEMBOK SAKTI: Pessimistic Locking untuk transaksi stok kritis
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ItemEntity i WHERE i.sku = :sku")
    Optional<ItemEntity> findBySkuForUpdate(@Param("sku") String sku);
}
package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.ItemEntity;
import jakarta.persistence.LockModeType; // WAJIB ADA
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock; // WAJIB ADA
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<ItemEntity, Long> {

    Optional<ItemEntity> findBySku(String sku);

    Page<ItemEntity> findAllByIsDeletedFalse(Pageable pageable);

    // --- LOGIC MAUT: PESSIMISTIC LOCKING ---
    // Gembok baris database ini sampai transaksi selesai agar stok tidak meleset
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ItemEntity i WHERE i.sku = :sku")
    Optional<ItemEntity> findBySkuForUpdate(@Param("sku") String sku);
}
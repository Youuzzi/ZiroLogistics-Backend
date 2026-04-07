package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.InventoryLedgerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryLedgerRepository extends JpaRepository<InventoryLedgerEntity, Long> {

    // Method untuk mengecek Idempotency
    boolean existsByRequestId(String requestId);

    // Industrial Standard: Ambil data riwayat ter-update dengan Pagination
    Page<InventoryLedgerEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
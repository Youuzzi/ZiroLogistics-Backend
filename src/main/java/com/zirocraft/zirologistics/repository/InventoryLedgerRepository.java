package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.InventoryLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryLedgerRepository extends JpaRepository<InventoryLedgerEntity, Long> {
    // Untuk cek Idempotency (Apakah request ID sudah pernah diproses?)
    boolean existsByRequestId(String requestId);
}
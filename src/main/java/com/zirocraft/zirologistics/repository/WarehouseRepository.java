package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.WarehouseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<WarehouseEntity, Long> {
    Optional<WarehouseEntity> findByPublicId(String publicId);
    Optional<WarehouseEntity> findByCode(String code);

    // Method untuk ambil data yang belum dihapus dengan fitur Pagination
    Page<WarehouseEntity> findAllByIsDeletedFalse(Pageable pageable);
}
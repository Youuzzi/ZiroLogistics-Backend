package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.WarehouseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<WarehouseEntity, Long> {
    Optional<WarehouseEntity> findByPublicId(String publicId);
    Optional<WarehouseEntity> findByCode(String code);
}
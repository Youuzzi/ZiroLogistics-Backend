package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.BinEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BinRepository extends JpaRepository<BinEntity, Long> {
    Optional<BinEntity> findByBinCode(String binCode);
    Optional<BinEntity> findByPublicId(String publicId);

    // LOGIC BARU: Filter langsung di Database (Industrial Standard)
    List<BinEntity> findByWarehousePublicIdAndIsDeletedFalse(String warehousePublicId);
}
package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.BinEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface BinRepository extends JpaRepository<BinEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BinEntity b WHERE b.binCode = :binCode")
    Optional<BinEntity> findByBinCodeForUpdate(@Param("binCode") String binCode);

    Optional<BinEntity> findByBinCode(String binCode);
    Optional<BinEntity> findByPublicId(String publicId);
    List<BinEntity> findByWarehousePublicIdAndIsDeletedFalse(String warehousePublicId);
}
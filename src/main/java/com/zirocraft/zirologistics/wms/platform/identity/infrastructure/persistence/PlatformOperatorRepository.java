package com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Internal repository for control-plane operator persistence. */
public interface PlatformOperatorRepository extends JpaRepository<PlatformOperatorEntity, Long> {
    Optional<PlatformOperatorEntity> findByPublicId(String publicId);
    Optional<PlatformOperatorEntity> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByPublicId(String publicId);
    long countByStatus(PlatformOperatorStatus status);
}

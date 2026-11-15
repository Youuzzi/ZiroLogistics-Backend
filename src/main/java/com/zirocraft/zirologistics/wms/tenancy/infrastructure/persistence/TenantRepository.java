package com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<TenantEntity, Long> {
    Optional<TenantEntity> findByPublicId(String publicId);
    Optional<TenantEntity> findByTenantCode(String tenantCode);
    boolean existsByTenantCode(String tenantCode);
}

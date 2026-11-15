package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.authorization.domain.RoleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByTenantIdAndRoleCode(Long tenantId, String roleCode);
    Optional<RoleEntity> findByPublicIdAndTenantId(String publicId, Long tenantId);
    List<RoleEntity> findAllByTenantIdAndStatus(Long tenantId, RoleStatus status);
}

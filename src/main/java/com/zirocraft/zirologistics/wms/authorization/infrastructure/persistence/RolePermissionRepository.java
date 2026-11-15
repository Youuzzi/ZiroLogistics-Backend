package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionId> {
    List<RolePermissionEntity> findAllByIdRoleId(Long roleId);
}

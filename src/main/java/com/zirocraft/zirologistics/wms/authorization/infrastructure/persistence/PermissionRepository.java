package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {
    Optional<PermissionEntity> findByPermissionCode(String permissionCode);
    List<PermissionEntity> findAllByPermissionCodeIn(Collection<String> permissionCodes);
    List<PermissionEntity> findAllByModule(String module);
}

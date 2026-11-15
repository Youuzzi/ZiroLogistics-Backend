package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MembershipRoleRepository extends JpaRepository<MembershipRoleEntity, MembershipRoleId> {
    List<MembershipRoleEntity> findAllByTenantIdAndIdMembershipId(Long tenantId, Long membershipId);
    boolean existsByTenantIdAndIdMembershipIdAndIdRoleId(Long tenantId, Long membershipId, Long roleId);
}

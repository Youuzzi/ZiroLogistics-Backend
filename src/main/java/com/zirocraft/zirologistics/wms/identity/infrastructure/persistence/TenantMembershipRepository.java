package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TenantMembershipRepository extends JpaRepository<TenantMembershipEntity, Long> {
    Optional<TenantMembershipEntity> findByPublicIdAndTenantId(String publicId, Long tenantId);
    Optional<TenantMembershipEntity> findByTenantIdAndUserId(Long tenantId, Long userId);
    List<TenantMembershipEntity> findAllByUserIdAndStatus(Long userId, MembershipStatus status);
    boolean existsByTenantIdAndUserIdAndStatus(Long tenantId, Long userId, MembershipStatus status);
}

package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read-only query for memberships the authenticated global user may select.
 */
public interface WmsLoginMembershipRepository extends Repository<TenantMembershipEntity, Long> {

    @Query(value = """
            SELECT
                m.public_id AS membershipPublicId,
                t.public_id AS tenantPublicId,
                t.tenant_code AS tenantCode,
                t.display_name AS tenantDisplayName
            FROM tenant_memberships m
            INNER JOIN tenants t
                ON t.id = m.tenant_id
            WHERE m.user_id = :userId
                AND m.status = 'ACTIVE'
                AND t.status = 'ACTIVE'
            ORDER BY t.display_name ASC, m.public_id ASC
            """, nativeQuery = true)
    List<WmsLoginMembershipProjection> findSelectableMemberships(@Param("userId") Long userId);
}

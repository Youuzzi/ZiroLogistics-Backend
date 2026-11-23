package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Resolves the current tenant access graph from authoritative database state.
 *
 * A caller must supply the user and membership identifiers from an already
 * authenticated identity; neither value alone grants access.
 */
public interface WmsMembershipAccessRepository extends Repository<TenantMembershipEntity, Long> {

    @Query(value = """
            SELECT DISTINCT
                m.id AS membershipId,
                m.tenant_id AS tenantId,
                m.user_id AS userId,
                m.public_id AS membershipPublicId,
                t.public_id AS tenantPublicId,
                u.public_id AS userPublicId,
                u.credential_version AS credentialVersion,
                p.permission_code AS permissionCode
            FROM tenant_memberships m
            INNER JOIN users u
                ON u.id = m.user_id
            INNER JOIN tenants t
                ON t.id = m.tenant_id
            LEFT JOIN membership_roles mr
                ON mr.membership_id = m.id
                AND mr.tenant_id = m.tenant_id
            LEFT JOIN roles r
                ON r.id = mr.role_id
                AND r.tenant_id = m.tenant_id
                AND r.status = 'ACTIVE'
            LEFT JOIN role_permissions rp
                ON rp.role_id = r.id
            LEFT JOIN permissions p
                ON p.id = rp.permission_id
            WHERE m.public_id = :membershipPublicId
                AND u.public_id = :userPublicId
                AND m.status = 'ACTIVE'
                AND u.status = 'ACTIVE'
                AND t.status = 'ACTIVE'
            """, nativeQuery = true)
    List<WmsMembershipAccessProjection> findActiveAccess(
            @Param("userPublicId") String userPublicId,
            @Param("membershipPublicId") String membershipPublicId);
}

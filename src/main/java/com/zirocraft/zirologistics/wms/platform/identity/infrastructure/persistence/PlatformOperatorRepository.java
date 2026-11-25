package com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Internal repository for control-plane operator persistence. */
public interface PlatformOperatorRepository extends JpaRepository<PlatformOperatorEntity, Long> {
    Optional<PlatformOperatorEntity> findByPublicId(String publicId);
    Optional<PlatformOperatorEntity> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByPublicId(String publicId);
    long countByStatus(PlatformOperatorStatus status);

    /**
     * Records successful authentication only while the operator remains ACTIVE,
     * email-verified, and on the credential version used for password verification.
     * Database UTC time avoids trusting an application-node clock.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE platform_operators
               SET last_authenticated_at = UTC_TIMESTAMP(6)
             WHERE id = :operatorId
               AND status = 'ACTIVE'
               AND email_verified_at IS NOT NULL
               AND credential_version = :credentialVersion
            """, nativeQuery = true)
    int recordSuccessfulAuthentication(
            @Param("operatorId") Long operatorId,
            @Param("credentialVersion") Long credentialVersion);
}

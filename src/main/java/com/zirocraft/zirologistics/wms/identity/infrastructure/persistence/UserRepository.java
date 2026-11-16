package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByPublicId(String publicId);
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Records a successful login only while the global user remains ACTIVE.
     * The version increment preserves the optimistic-lock contract for JPA writers.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
            SET last_login_at = UTC_TIMESTAMP(6),
                version = version + 1
            WHERE id = :userId
              AND status = 'ACTIVE'
            """, nativeQuery = true)
    int recordSuccessfulLogin(@Param("userId") Long userId);
}

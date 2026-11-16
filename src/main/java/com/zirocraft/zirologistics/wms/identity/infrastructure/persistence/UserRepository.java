package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByPublicId(String publicId);
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Acquires the global user row lock. Account action-token operations use
     * this lock before locking or revoking any token rows for the same user.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :userId")
    Optional<UserEntity> findByIdForUpdate(@Param("userId") Long userId);

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

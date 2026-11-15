package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByPublicId(String publicId);
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}

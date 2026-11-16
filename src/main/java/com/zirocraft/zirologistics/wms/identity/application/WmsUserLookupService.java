package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Centralizes canonical email handling for WMS identity lookups.
 * It deliberately does not authenticate users or expose an HTTP endpoint.
 */
@Service
@Profile("wms")
public class WmsUserLookupService {

    private final UserRepository userRepository;

    public WmsUserLookupService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(WmsEmailNormalizer.normalize(email));
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(WmsEmailNormalizer.normalize(email));
    }

    /**
     * Updates login metadata only if the user is still ACTIVE at write time.
     * A false result must prevent authentication token issuance.
     */
    @Transactional
    public boolean recordSuccessfulLogin(Long userId) {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return userRepository.recordSuccessfulLogin(userId) == 1;
    }
}

package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReplayProtectionPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * MySQL atomic compare-and-set for the per-factor TOTP replay watermark.
 *
 * The UPDATE is a single InnoDB statement, so concurrent verifications of the
 * same time step cannot both win. Callers that also create a step-up proof
 * must invoke this adapter inside the same surrounding transaction.
 */
public final class MySqlWmsTenantMfaReplayProtectionAdapter
        implements WmsTenantMfaReplayProtectionPort {

    private static final String ACCEPT_TIME_STEP = """
            UPDATE wms_tenant_mfa_factors factor
            INNER JOIN users account
                ON account.id = factor.user_id
               AND account.status = 'ACTIVE'
            SET factor.last_accepted_time_step = ?
            WHERE factor.id = ?
              AND factor.user_id = ?
              AND factor.factor_type = 'TOTP'
              AND factor.status = 'ACTIVE'
              AND factor.last_accepted_time_step < ?
              AND (factor.locked_until IS NULL OR factor.locked_until <= UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    public MySqlWmsTenantMfaReplayProtectionAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public boolean acceptTimeStep(long factorId, long userId, long matchedTimeStep) {
        if (factorId < 1 || userId < 1) {
            throw new IllegalArgumentException("factorId and userId must be positive");
        }
        if (matchedTimeStep < 0) {
            throw new IllegalArgumentException("matchedTimeStep must not be negative");
        }
        return jdbcTemplate.update(ACCEPT_TIME_STEP,
                matchedTimeStep, factorId, userId, matchedTimeStep) == 1;
    }
}

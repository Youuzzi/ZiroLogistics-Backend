-- Additive WMS V31 tenant MFA factor, challenge, proof, recovery and audit storage.
-- V1-V30 are frozen. This migration is scoped to the WMS runtime database only.
-- Never run against zirowms_v1 or zirologistics_db.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- Parent key allows child rows to prove that the selected membership belongs
-- to both the recorded tenant and the recorded global user.
ALTER TABLE tenant_memberships
    ADD UNIQUE KEY uk_membership_identity_scope (id, tenant_id, user_id);

CREATE TABLE wms_tenant_mfa_factors (
    id bigint NOT NULL AUTO_INCREMENT,
    public_id char(36) NOT NULL,
    user_id bigint NOT NULL,
    factor_type varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    seed_nonce binary(12) DEFAULT NULL,
    encrypted_seed varbinary(512) DEFAULT NULL,
    encryption_key_version varchar(64) DEFAULT NULL,
    last_accepted_time_step bigint NOT NULL DEFAULT -1,
    failed_attempt_count int unsigned NOT NULL DEFAULT 0,
    locked_until datetime(6) DEFAULT NULL,
    pending_expires_at datetime(6) DEFAULT NULL,
    activated_at datetime(6) DEFAULT NULL,
    revoked_at datetime(6) DEFAULT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_factor_public_id (public_id),
    UNIQUE KEY uk_wms_tenant_mfa_factor_identity (id, user_id),
    KEY idx_wms_tenant_mfa_factor_user_state (user_id, status, created_at),
    KEY idx_wms_tenant_mfa_factor_lock (status, locked_until),
    CONSTRAINT fk_wms_tenant_mfa_factor_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_wms_tenant_mfa_factor_type
        CHECK (factor_type IN ('TOTP')),
    CONSTRAINT chk_wms_tenant_mfa_factor_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'REVOKED')),
    CONSTRAINT chk_wms_tenant_mfa_factor_seed_state
        CHECK (
            (status IN ('PENDING', 'ACTIVE', 'LOCKED')
                AND seed_nonce IS NOT NULL
                AND encrypted_seed IS NOT NULL
                AND encryption_key_version IS NOT NULL)
            OR
            (status = 'REVOKED'
                AND seed_nonce IS NULL
                AND encrypted_seed IS NULL
                AND encryption_key_version IS NULL)
        ),
    CONSTRAINT chk_wms_tenant_mfa_factor_time_step
        CHECK (last_accepted_time_step >= -1),
    CONSTRAINT chk_wms_tenant_mfa_factor_revoked
        CHECK ((status = 'REVOKED' AND revoked_at IS NOT NULL)
            OR (status <> 'REVOKED' AND revoked_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wms_tenant_mfa_recovery_codes (
    id bigint NOT NULL AUTO_INCREMENT,
    factor_id bigint NOT NULL,
    user_id bigint NOT NULL,
    code_digest binary(32) NOT NULL,
    digest_key_version varchar(64) NOT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumed_at datetime(6) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_recovery_digest (code_digest),
    KEY idx_wms_tenant_mfa_recovery_factor (factor_id, user_id, consumed_at),
    CONSTRAINT fk_wms_tenant_mfa_recovery_factor
        FOREIGN KEY (factor_id, user_id)
        REFERENCES wms_tenant_mfa_factors (id, user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wms_tenant_mfa_step_up_challenges (
    id bigint NOT NULL AUTO_INCREMENT,
    public_id char(36) NOT NULL,
    challenge_digest binary(32) NOT NULL,
    user_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    membership_id bigint NOT NULL,
    credential_version bigint NOT NULL,
    purpose varchar(60) NOT NULL,
    expires_at datetime(6) NOT NULL,
    attempt_count int unsigned NOT NULL DEFAULT 0,
    consumed_at datetime(6) DEFAULT NULL,
    revoked_at datetime(6) DEFAULT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_challenge_public_id (public_id),
    UNIQUE KEY uk_wms_tenant_mfa_challenge_digest (challenge_digest),
    KEY idx_wms_tenant_mfa_challenge_membership (tenant_id, membership_id, purpose, expires_at),
    KEY idx_wms_tenant_mfa_challenge_cleanup (expires_at, consumed_at, revoked_at),
    CONSTRAINT fk_wms_tenant_mfa_challenge_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_challenge_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_challenge_membership
        FOREIGN KEY (membership_id, tenant_id, user_id)
        REFERENCES tenant_memberships (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_wms_tenant_mfa_challenge_purpose
        CHECK (purpose IN (
            'SINGLE_OPERATOR_ACTIVATION',
            'SINGLE_OPERATOR_DEACTIVATION',
            'SINGLE_OPERATOR_ROLE_SELECTION',
            'TENANT_GOVERNANCE_ROLE_CHANGE',
            'MFA_FACTOR_ENROLLMENT',
            'MFA_FACTOR_REVOCATION',
            'MFA_FACTOR_RECOVERY'
        )),
    CONSTRAINT chk_wms_tenant_mfa_challenge_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT chk_wms_tenant_mfa_challenge_attempts
        CHECK (attempt_count <= 10),
    CONSTRAINT chk_wms_tenant_mfa_challenge_terminal_state
        CHECK (consumed_at IS NULL OR revoked_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wms_tenant_mfa_step_up_proofs (
    id bigint NOT NULL AUTO_INCREMENT,
    proof_digest binary(32) NOT NULL,
    user_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    membership_id bigint NOT NULL,
    credential_version bigint NOT NULL,
    purpose varchar(60) NOT NULL,
    verified_at datetime(6) NOT NULL,
    expires_at datetime(6) NOT NULL,
    consumed_at datetime(6) DEFAULT NULL,
    revoked_at datetime(6) DEFAULT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_proof_digest (proof_digest),
    KEY idx_wms_tenant_mfa_proof_membership (tenant_id, membership_id, purpose, expires_at),
    KEY idx_wms_tenant_mfa_proof_cleanup (expires_at, consumed_at, revoked_at),
    CONSTRAINT fk_wms_tenant_mfa_proof_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_proof_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_proof_membership
        FOREIGN KEY (membership_id, tenant_id, user_id)
        REFERENCES tenant_memberships (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_wms_tenant_mfa_proof_purpose
        CHECK (purpose IN (
            'SINGLE_OPERATOR_ACTIVATION',
            'SINGLE_OPERATOR_DEACTIVATION',
            'SINGLE_OPERATOR_ROLE_SELECTION',
            'TENANT_GOVERNANCE_ROLE_CHANGE',
            'MFA_FACTOR_ENROLLMENT',
            'MFA_FACTOR_REVOCATION',
            'MFA_FACTOR_RECOVERY'
        )),
    CONSTRAINT chk_wms_tenant_mfa_proof_expiry
        CHECK (expires_at > verified_at),
    CONSTRAINT chk_wms_tenant_mfa_proof_terminal_state
        CHECK (consumed_at IS NULL OR revoked_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wms_tenant_mfa_throttle_buckets (
    id bigint NOT NULL AUTO_INCREMENT,
    bucket_type varchar(20) NOT NULL,
    key_version int unsigned NOT NULL,
    bucket_key_hash binary(32) NOT NULL,
    window_started_at datetime(6) NOT NULL,
    attempt_count int unsigned NOT NULL DEFAULT 0,
    blocked_until datetime(6) DEFAULT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_throttle_window
        (bucket_type, key_version, bucket_key_hash, window_started_at),
    KEY idx_wms_tenant_mfa_throttle_blocked (bucket_type, blocked_until),
    CONSTRAINT chk_wms_tenant_mfa_throttle_bucket_type
        CHECK (bucket_type IN ('ACCOUNT', 'FACTOR', 'SOURCE')),
    CONSTRAINT chk_wms_tenant_mfa_throttle_key_version
        CHECK (key_version > 0),
    CONSTRAINT chk_wms_tenant_mfa_throttle_attempts
        CHECK (attempt_count <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Tenant-user security events are separate from the older organization audit
-- table's fixed event-type allowlist. This table records no secrets or OTP data.
CREATE TABLE tenant_security_audit_events (
    id bigint NOT NULL AUTO_INCREMENT,
    public_id char(36) NOT NULL,
    tenant_id bigint NOT NULL,
    actor_membership_id bigint NOT NULL,
    actor_user_id bigint NOT NULL,
    subject_user_id bigint NOT NULL,
    operation_ref char(36) DEFAULT NULL,
    event_type varchar(60) NOT NULL,
    event_payload json NOT NULL,
    occurred_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_security_audit_public_id (public_id),
    UNIQUE KEY uk_tenant_security_audit_operation_ref (operation_ref),
    KEY idx_tenant_security_audit_tenant_time (tenant_id, occurred_at),
    KEY idx_tenant_security_audit_subject_time (tenant_id, subject_user_id, occurred_at),
    CONSTRAINT fk_tenant_security_audit_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tenant_security_audit_actor_membership
        FOREIGN KEY (actor_membership_id, tenant_id, actor_user_id)
        REFERENCES tenant_memberships (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_tenant_security_audit_subject_user
        FOREIGN KEY (subject_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_tenant_security_audit_event_type
        CHECK (event_type IN (
            'MFA_ENROLLMENT_STARTED',
            'MFA_ENROLLMENT_COMPLETED',
            'MFA_ENROLLMENT_EXPIRED',
            'MFA_STEP_UP_SUCCEEDED',
            'MFA_STEP_UP_FAILED',
            'MFA_RECOVERY_CODE_USED',
            'MFA_FACTOR_REVOKED',
            'MFA_FACTOR_REPLACED',
            'MFA_STEP_UP_PROOF_CONSUMED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

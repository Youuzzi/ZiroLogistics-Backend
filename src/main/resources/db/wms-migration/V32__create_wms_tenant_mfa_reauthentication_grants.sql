-- Additive WMS V32: one-time grants proving recent tenant password reauthentication.
-- V1-V31 are frozen. No password, token, or plaintext grant material is stored.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE wms_tenant_mfa_reauthentication_grants (
    id bigint NOT NULL AUTO_INCREMENT,
    public_id char(36) NOT NULL,
    grant_digest binary(32) NOT NULL,
    user_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    membership_id bigint NOT NULL,
    credential_version bigint NOT NULL,
    purpose varchar(40) NOT NULL,
    expires_at datetime(6) NOT NULL,
    consumed_at datetime(6) DEFAULT NULL,
    revoked_at datetime(6) DEFAULT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_tenant_mfa_reauth_public_id (public_id),
    UNIQUE KEY uk_wms_tenant_mfa_reauth_digest (grant_digest),
    KEY idx_wms_tenant_mfa_reauth_identity (tenant_id, membership_id, purpose, expires_at),
    KEY idx_wms_tenant_mfa_reauth_cleanup (expires_at, consumed_at, revoked_at),
    CONSTRAINT fk_wms_tenant_mfa_reauth_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_reauth_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_wms_tenant_mfa_reauth_membership
        FOREIGN KEY (membership_id, tenant_id, user_id)
        REFERENCES tenant_memberships (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_wms_tenant_mfa_reauth_purpose
        CHECK (purpose IN (
            'MFA_FACTOR_ENROLLMENT',
            'MFA_FACTOR_REVOCATION',
            'MFA_FACTOR_RECOVERY'
        )),
    CONSTRAINT chk_wms_tenant_mfa_reauth_expiry
        CHECK (expires_at > created_at
            AND expires_at <= DATE_ADD(created_at, INTERVAL 5 MINUTE)),
    CONSTRAINT chk_wms_tenant_mfa_reauth_terminal_state
        CHECK (consumed_at IS NULL OR revoked_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

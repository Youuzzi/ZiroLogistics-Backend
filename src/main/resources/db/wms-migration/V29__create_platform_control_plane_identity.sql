-- Separate SaaS control-plane identity and authorization schema.
-- No tenant identity, tenant role, or tenant permission is reused as platform authority.
-- Platform role-permission grants and the first platform operator are provisioned by later
-- controlled implementation steps; this migration only establishes the isolated data model.

CREATE TABLE platform_operators (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(160) NOT NULL,
  status VARCHAR(20) NOT NULL,
  credential_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  email_verified_at DATETIME(6) DEFAULT NULL,
  last_authenticated_at DATETIME(6) DEFAULT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_operators_public_id (public_id),
  UNIQUE KEY uk_platform_operators_email (email),
  KEY idx_platform_operators_status (status),
  CONSTRAINT chk_platform_operators_status
    CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DISABLED')),
  CONSTRAINT chk_platform_operators_credential_version
    CHECK (credential_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_roles (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  role_code VARCHAR(80) NOT NULL,
  role_name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NOT NULL,
  is_system_role TINYINT(1) NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_roles_public_id (public_id),
  UNIQUE KEY uk_platform_roles_code (role_code),
  CONSTRAINT chk_platform_roles_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT chk_platform_roles_system CHECK (is_system_role IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_permissions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  permission_code VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_permissions_code (permission_code),
  CONSTRAINT chk_platform_permissions_risk
    CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  CONSTRAINT chk_platform_permissions_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_operator_roles (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  assigned_by_operator_id BIGINT DEFAULT NULL,
  assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) DEFAULT NULL,
  revoked_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_operator_roles_assignment (operator_id, role_id, assigned_at),
  KEY idx_platform_operator_roles_effective (operator_id, revoked_at, expires_at),
  KEY idx_platform_operator_roles_role (role_id),
  CONSTRAINT fk_platform_operator_roles_operator
    FOREIGN KEY (operator_id) REFERENCES platform_operators (id),
  CONSTRAINT fk_platform_operator_roles_role
    FOREIGN KEY (role_id) REFERENCES platform_roles (id),
  CONSTRAINT fk_platform_operator_roles_assigner
    FOREIGN KEY (assigned_by_operator_id) REFERENCES platform_operators (id),
  CONSTRAINT chk_platform_operator_roles_not_self_assigned
    CHECK (assigned_by_operator_id IS NULL OR assigned_by_operator_id <> operator_id),
  CONSTRAINT chk_platform_operator_roles_expiry
    CHECK (expires_at IS NULL OR expires_at > assigned_at),
  CONSTRAINT chk_platform_operator_roles_revocation
    CHECK (revoked_at IS NULL OR revoked_at >= assigned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_role_permissions (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_platform_role_permissions_role
    FOREIGN KEY (role_id) REFERENCES platform_roles (id),
  CONSTRAINT fk_platform_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES platform_permissions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_operator_mfa_credentials (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  secret_ciphertext VARBINARY(512) NOT NULL,
  secret_nonce VARBINARY(12) NOT NULL,
  encryption_key_version SMALLINT UNSIGNED NOT NULL,
  enrolled_at DATETIME(6) DEFAULT NULL,
  last_used_at DATETIME(6) DEFAULT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_operator_mfa_operator (operator_id),
  CONSTRAINT fk_platform_operator_mfa_operator
    FOREIGN KEY (operator_id) REFERENCES platform_operators (id),
  CONSTRAINT chk_platform_operator_mfa_nonce CHECK (OCTET_LENGTH(secret_nonce) = 12),
  CONSTRAINT chk_platform_operator_mfa_key_version CHECK (encryption_key_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_operator_recovery_codes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  consumed_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_operator_recovery_code_hash (code_hash),
  KEY idx_platform_operator_recovery_codes_operator (operator_id, consumed_at),
  CONSTRAINT fk_platform_operator_recovery_codes_operator
    FOREIGN KEY (operator_id) REFERENCES platform_operators (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_authentication_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  event_type VARCHAR(60) NOT NULL,
  outcome VARCHAR(20) NOT NULL,
  source_address_hash BINARY(32) DEFAULT NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  event_payload JSON DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_authentication_events_public_id (public_id),
  KEY idx_platform_authentication_events_operator_time (operator_id, occurred_at),
  KEY idx_platform_authentication_events_type_time (event_type, occurred_at),
  CONSTRAINT fk_platform_authentication_events_operator
    FOREIGN KEY (operator_id) REFERENCES platform_operators (id),
  CONSTRAINT chk_platform_authentication_events_outcome
    CHECK (outcome IN ('SUCCESS', 'FAILURE', 'CHALLENGE', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_audit_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  actor_operator_id BIGINT NOT NULL,
  operation_ref CHAR(36) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  subject_type VARCHAR(60) NOT NULL,
  subject_public_id CHAR(36) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  change_payload JSON NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_audit_events_public_id (public_id),
  UNIQUE KEY uk_platform_audit_events_operation_ref (operation_ref),
  KEY idx_platform_audit_events_actor_time (actor_operator_id, occurred_at),
  KEY idx_platform_audit_events_subject (subject_type, subject_public_id, occurred_at),
  CONSTRAINT fk_platform_audit_events_actor
    FOREIGN KEY (actor_operator_id) REFERENCES platform_operators (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform_roles (public_id, role_code, role_name, description, is_system_role, status)
VALUES
  (UUID(), 'PLATFORM_OWNER', 'Platform Owner', 'Platform governance and operator lifecycle oversight.', 1, 'ACTIVE'),
  (UUID(), 'PLATFORM_ADMIN', 'Platform Administrator', 'Platform configuration and tenant lifecycle coordination.', 1, 'ACTIVE'),
  (UUID(), 'PLATFORM_SUPPORT', 'Platform Support', 'Customer support through explicit, approved, time-limited grants.', 1, 'ACTIVE'),
  (UUID(), 'PLATFORM_BILLING_ADMIN', 'Platform Billing Administrator', 'Future plan, subscription, invoice, and payment administration.', 1, 'ACTIVE'),
  (UUID(), 'PLATFORM_SECURITY_ADMIN', 'Platform Security Administrator', 'Platform security policy and incident-response administration.', 1, 'ACTIVE'),
  (UUID(), 'PLATFORM_AUDITOR', 'Platform Auditor', 'Read-only platform audit and compliance evidence.', 1, 'ACTIVE');

INSERT INTO platform_permissions (permission_code, description, risk_level, status)
VALUES
  ('PLATFORM_OPERATOR_READ', 'Read platform operator identities and status.', 'LOW', 'ACTIVE'),
  ('PLATFORM_OPERATOR_INVITE', 'Invite a new platform operator.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_OPERATOR_SUSPEND', 'Suspend or disable a platform operator.', 'CRITICAL', 'ACTIVE'),
  ('PLATFORM_OPERATOR_ROLE_ASSIGN', 'Assign or revoke platform roles.', 'CRITICAL', 'ACTIVE'),
  ('PLATFORM_TENANT_READ', 'Read tenant lifecycle and platform metadata.', 'LOW', 'ACTIVE'),
  ('PLATFORM_TENANT_PROVISION', 'Provision a tenant through the controlled platform workflow.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_TENANT_SUSPEND', 'Suspend an active tenant.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_TENANT_REACTIVATE', 'Reactivate a suspended tenant.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_TENANT_CLOSE', 'Close a tenant through a confirmed, audited operation.', 'CRITICAL', 'ACTIVE'),
  ('PLATFORM_TENANT_METADATA_READ', 'Read tenant platform metadata.', 'LOW', 'ACTIVE'),
  ('PLATFORM_TENANT_METADATA_UPDATE', 'Update tenant platform metadata.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_SUPPORT_GRANT_CREATE', 'Create an approved, scoped, time-limited support grant.', 'CRITICAL', 'ACTIVE'),
  ('PLATFORM_SUPPORT_GRANT_REVOKE', 'Revoke an active support grant.', 'HIGH', 'ACTIVE'),
  ('PLATFORM_AUDIT_READ', 'Read platform audit events.', 'LOW', 'ACTIVE'),
  ('PLATFORM_SECURITY_POLICY_MANAGE', 'Manage platform security policy.', 'CRITICAL', 'ACTIVE');

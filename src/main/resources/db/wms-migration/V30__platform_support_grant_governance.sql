-- Support access is a control-plane approval record, not tenant impersonation.
-- Grants are tenant-bound, purpose-bound, time-limited and scope-limited.
-- No warehouse transaction scopes or support session/impersonation mechanism is introduced.

CREATE TABLE platform_support_grants (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  requested_by_operator_id BIGINT NOT NULL,
  approved_by_operator_id BIGINT DEFAULT NULL,
  revoked_by_operator_id BIGINT DEFAULT NULL,
  operation_ref CHAR(36) NOT NULL,
  purpose VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  starts_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  approved_at DATETIME(6) DEFAULT NULL,
  revoked_at DATETIME(6) DEFAULT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_support_grants_public_id (public_id),
  UNIQUE KEY uk_platform_support_grants_operation_ref (operation_ref),
  KEY idx_platform_support_grants_tenant_status (tenant_id, status, expires_at),
  KEY idx_platform_support_grants_requester (requested_by_operator_id, requested_at),
  KEY idx_platform_support_grants_approver (approved_by_operator_id, approved_at),
  CONSTRAINT fk_platform_support_grants_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id),
  CONSTRAINT fk_platform_support_grants_requester
    FOREIGN KEY (requested_by_operator_id) REFERENCES platform_operators (id),
  CONSTRAINT fk_platform_support_grants_approver
    FOREIGN KEY (approved_by_operator_id) REFERENCES platform_operators (id),
  CONSTRAINT fk_platform_support_grants_revoker
    FOREIGN KEY (revoked_by_operator_id) REFERENCES platform_operators (id),
  CONSTRAINT chk_platform_support_grants_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED')),
  CONSTRAINT chk_platform_support_grants_purpose
    CHECK (CHAR_LENGTH(TRIM(purpose)) BETWEEN 10 AND 500),
  CONSTRAINT chk_platform_support_grants_window
    CHECK (expires_at > starts_at),
  CONSTRAINT chk_platform_support_grants_approval
    CHECK (approved_by_operator_id IS NULL OR approved_by_operator_id <> requested_by_operator_id),
  CONSTRAINT chk_platform_support_grants_approved_state
    CHECK (status NOT IN ('APPROVED', 'REVOKED', 'EXPIRED') OR
           (approved_by_operator_id IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT chk_platform_support_grants_revoked_state
    CHECK (status <> 'REVOKED' OR (revoked_by_operator_id IS NOT NULL AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_support_grant_scopes (
  grant_id BIGINT NOT NULL,
  scope_code VARCHAR(60) NOT NULL,
  granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (grant_id, scope_code),
  CONSTRAINT fk_platform_support_grant_scopes_grant
    FOREIGN KEY (grant_id) REFERENCES platform_support_grants (id),
  CONSTRAINT chk_platform_support_grant_scope
    CHECK (scope_code IN (
      'TENANT_METADATA_READ',
      'TENANT_CONFIGURATION_READ',
      'INTEGRATION_DIAGNOSTICS_READ',
      'TENANT_AUDIT_METADATA_READ'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

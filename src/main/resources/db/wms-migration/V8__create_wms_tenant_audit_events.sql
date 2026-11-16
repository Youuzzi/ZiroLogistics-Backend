-- Append-only tenant organization audit records. V1-V7 remain frozen.
CREATE TABLE tenant_audit_events (
  id bigint NOT NULL AUTO_INCREMENT,
  public_id char(36) NOT NULL,
  tenant_id bigint NOT NULL,
  actor_membership_id bigint DEFAULT NULL,
  actor_type varchar(30) NOT NULL,
  actor_reference varchar(120) DEFAULT NULL,
  operation_ref char(36) DEFAULT NULL,
  event_type varchar(80) NOT NULL,
  subject_public_id char(36) NOT NULL,
  change_payload json NOT NULL,
  occurred_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_audit_events_public_id (public_id),
  UNIQUE KEY uk_tenant_audit_events_operation_ref (operation_ref),
  KEY idx_tenant_audit_events_tenant_time (tenant_id, occurred_at),
  KEY idx_tenant_audit_events_subject (tenant_id, subject_public_id, occurred_at),
  CONSTRAINT fk_tenant_audit_events_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
  CONSTRAINT fk_tenant_audit_events_actor_membership
    FOREIGN KEY (actor_membership_id, tenant_id)
    REFERENCES tenant_memberships (id, tenant_id) ON DELETE RESTRICT,
  CONSTRAINT chk_tenant_audit_events_actor_type
    CHECK (actor_type IN ('TENANT_MEMBER', 'PLATFORM_OPERATOR')),
  CONSTRAINT chk_tenant_audit_events_actor_reference
    CHECK (
      (actor_type = 'TENANT_MEMBER' AND actor_membership_id IS NOT NULL AND actor_reference IS NULL)
      OR
      (actor_type = 'PLATFORM_OPERATOR' AND actor_membership_id IS NULL AND actor_reference IS NOT NULL)
    ),
  CONSTRAINT chk_tenant_audit_events_type
    CHECK (event_type IN ('TENANT_PROFILE_UPDATED', 'TENANT_SUSPENDED', 'TENANT_REACTIVATED', 'TENANT_CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

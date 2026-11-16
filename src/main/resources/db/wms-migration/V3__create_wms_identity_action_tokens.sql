-- Additive WMS V3 durable state for invitations, email verification, and password reset.
-- WMS V1 is frozen; V2 remains unchanged.
-- This migration is validated only with disposable MySQL 8.4 Testcontainers databases.
-- Never run against zirowms_v1 or zirologistics_db.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `wms_identity_action_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `token_digest` binary(32) NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `tenant_id` bigint DEFAULT NULL,
  `invited_by_user_id` bigint DEFAULT NULL,
  `target_email` varchar(254) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wms_identity_action_token_digest` (`token_digest`),
  KEY `idx_wms_identity_action_token_user_purpose` (`user_id`, `purpose`, `created_at`),
  KEY `idx_wms_identity_action_token_tenant_email` (`tenant_id`, `target_email`, `purpose`, `created_at`),
  KEY `idx_wms_identity_action_token_cleanup` (`expires_at`, `consumed_at`, `revoked_at`),
  CONSTRAINT `chk_wms_identity_action_token_purpose`
    CHECK (`purpose` IN ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET')),
  CONSTRAINT `chk_wms_identity_action_token_binding`
    CHECK (
      (
        `purpose` = 'INVITATION'
        AND `tenant_id` IS NOT NULL
        AND `invited_by_user_id` IS NOT NULL
        AND `target_email` IS NOT NULL
      )
      OR
      (
        `purpose` IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')
        AND `user_id` IS NOT NULL
        AND `tenant_id` IS NULL
        AND `invited_by_user_id` IS NULL
        AND `target_email` IS NOT NULL
      )
    ),
  CONSTRAINT `chk_wms_identity_action_token_terminal_state`
    CHECK (`consumed_at` IS NULL OR `revoked_at` IS NULL),
  CONSTRAINT `chk_wms_identity_action_token_expiry`
    CHECK (`expires_at` > `created_at`),
  CONSTRAINT `fk_wms_identity_action_token_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_wms_identity_action_token_tenant`
    FOREIGN KEY (`tenant_id`) REFERENCES `tenants` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_wms_identity_action_token_inviter`
    FOREIGN KEY (`invited_by_user_id`) REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

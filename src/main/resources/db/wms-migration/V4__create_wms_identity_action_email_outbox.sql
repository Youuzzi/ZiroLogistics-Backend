-- Additive WMS V4 transactional email outbox.
-- Bearer tokens and message payloads are encrypted before persistence; raw token material is never stored.
-- WMS V1/V2/V3 are immutable and remain unchanged.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `wms_identity_action_email_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` binary(16) NOT NULL,
  `message_type` varchar(32) NOT NULL,
  `recipient_email` varchar(254) NOT NULL,
  `payload_ciphertext` varbinary(4096) NOT NULL,
  `payload_nonce` binary(12) NOT NULL,
  `encryption_key_version` smallint unsigned NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT 0,
  `available_at` datetime(6) NOT NULL,
  `locked_at` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `last_error_code` varchar(64) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wms_identity_email_outbox_message_id` (`message_id`),
  KEY `idx_wms_identity_email_outbox_claim` (`status`, `available_at`, `id`),
  KEY `idx_wms_identity_email_outbox_recipient` (`recipient_email`, `message_type`, `created_at`),
  CONSTRAINT `chk_wms_identity_email_outbox_message_type`
    CHECK (`message_type` IN ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET')),
  CONSTRAINT `chk_wms_identity_email_outbox_status`
    CHECK (`status` IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD_LETTER')),
  CONSTRAINT `chk_wms_identity_email_outbox_attempt_count`
    CHECK (`attempt_count` >= 0),
  CONSTRAINT `chk_wms_identity_email_outbox_key_version`
    CHECK (`encryption_key_version` > 0),
  CONSTRAINT `chk_wms_identity_email_outbox_ciphertext`
    CHECK (OCTET_LENGTH(`payload_ciphertext`) > 16),
  CONSTRAINT `chk_wms_identity_email_outbox_state`
    CHECK (
      (`status` = 'PENDING' AND `locked_at` IS NULL AND `sent_at` IS NULL)
      OR
      (`status` = 'PROCESSING' AND `locked_at` IS NOT NULL AND `sent_at` IS NULL)
      OR
      (`status` = 'SENT' AND `locked_at` IS NULL AND `sent_at` IS NOT NULL)
      OR
      (`status` = 'DEAD_LETTER' AND `locked_at` IS NULL AND `sent_at` IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

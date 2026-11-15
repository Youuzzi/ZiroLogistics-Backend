-- Additive WMS V2 persistence for privacy-preserving login throttle buckets.
-- WMS V1 and its source DDL remain frozen and must never be edited.
-- This migration is exercised only against disposable MySQL 8.4 Testcontainers databases.
-- Never run migrations or tests against zirowms_v1 or zirologistics_db.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `wms_login_throttle_buckets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bucket_type` varchar(16) NOT NULL,
  `key_version` smallint unsigned NOT NULL,
  `bucket_key_hash` binary(32) NOT NULL,
  `window_started_at` datetime(6) NOT NULL,
  `attempt_count` int unsigned NOT NULL DEFAULT '0',
  `blocked_until` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wms_login_throttle_bucket_window`
    (`bucket_type`,`key_version`,`bucket_key_hash`,`window_started_at`),
  KEY `idx_wms_login_throttle_account_cooldown`
    (`bucket_type`,`key_version`,`bucket_key_hash`,`blocked_until`),
  KEY `idx_wms_login_throttle_cleanup`
    (`window_started_at`,`blocked_until`),
  CONSTRAINT `chk_wms_login_throttle_bucket_type`
    CHECK (`bucket_type` IN ('ACCOUNT','SOURCE')),
  CONSTRAINT `chk_wms_login_throttle_key_version`
    CHECK (`key_version` > 0),
  CONSTRAINT `chk_wms_login_throttle_source_not_blocked`
    CHECK ((`bucket_type` = 'ACCOUNT') OR (`blocked_until` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

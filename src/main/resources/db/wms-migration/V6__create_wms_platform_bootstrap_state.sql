-- Additive one-time WMS platform bootstrap guard.
-- The singleton row serializes concurrent bootstrap attempts and records the
-- first tenant/global administrator created by the controlled operator flow.
-- V1-V5 remain immutable.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `wms_platform_bootstrap_state` (
  `id` tinyint unsigned NOT NULL,
  `bootstrap_tenant_id` bigint DEFAULT NULL,
  `bootstrap_user_id` bigint DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wms_platform_bootstrap_tenant` (`bootstrap_tenant_id`),
  UNIQUE KEY `uk_wms_platform_bootstrap_user` (`bootstrap_user_id`),
  CONSTRAINT `chk_wms_platform_bootstrap_singleton` CHECK (`id` = 1),
  CONSTRAINT `chk_wms_platform_bootstrap_completion` CHECK (
    (`completed_at` IS NULL AND `bootstrap_tenant_id` IS NULL AND `bootstrap_user_id` IS NULL)
    OR
    (`completed_at` IS NOT NULL AND `bootstrap_tenant_id` IS NOT NULL AND `bootstrap_user_id` IS NOT NULL)
  ),
  CONSTRAINT `fk_wms_platform_bootstrap_tenant`
    FOREIGN KEY (`bootstrap_tenant_id`) REFERENCES `tenants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_wms_platform_bootstrap_user`
    FOREIGN KEY (`bootstrap_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `wms_platform_bootstrap_state` (`id`) VALUES (1);

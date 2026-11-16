-- One-time operator bootstrap guard. V1-V5 remain frozen.
CREATE TABLE `wms_platform_bootstrap_state` (
  `id` tinyint unsigned NOT NULL,
  `status` varchar(20) NOT NULL,
  `operation_ref` char(36) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_wms_platform_bootstrap_state_singleton` CHECK (`id` = 1),
  CONSTRAINT `chk_wms_platform_bootstrap_state_status` CHECK (
    (`status` = 'NOT_COMPLETED' AND `operation_ref` IS NULL AND `completed_at` IS NULL)
    OR
    (`status` = 'COMPLETED' AND `operation_ref` IS NOT NULL AND `completed_at` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `wms_platform_bootstrap_state` (`id`, `status`)
VALUES (1, 'NOT_COMPLETED');

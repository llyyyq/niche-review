CREATE TABLE IF NOT EXISTS `tb_ai_knowledge_sync_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `shop_id` bigint unsigned NOT NULL COMMENT 'shop whose knowledge must be synchronized',
  `status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 pending, 1 processing, 2 succeeded, 3 failed',
  `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT 'number of attempted synchronizations',
  `next_retry_at` timestamp NULL DEFAULT NULL COMMENT 'next scheduled attempt',
  `processing_at` timestamp NULL DEFAULT NULL COMMENT 'claim time for timeout recovery',
  `last_error` varchar(512) DEFAULT NULL COMMENT 'last synchronization failure',
  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'change version to avoid losing concurrent updates',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_knowledge_sync_task_shop_id` (`shop_id`),
  KEY `idx_ai_knowledge_sync_task_due` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable retry tasks for AI knowledge synchronization';

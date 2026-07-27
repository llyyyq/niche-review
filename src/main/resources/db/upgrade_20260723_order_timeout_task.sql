-- Run once on an existing hmdp database.
-- Historical unpaid orders are intentionally not inserted into this table.
CREATE TABLE IF NOT EXISTS `tb_order_timeout_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `order_id` bigint unsigned NOT NULL COMMENT 'voucher order id',
  `due_at` datetime NOT NULL COMMENT 'payment deadline',
  `status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 pending, 1 processing, 2 sent, 3 cancelled',
  `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT 'delivery attempts',
  `next_retry_at` datetime NOT NULL COMMENT 'next delivery attempt',
  `processing_at` datetime DEFAULT NULL COMMENT 'claim time for timeout recovery',
  `last_error` varchar(512) DEFAULT NULL COMMENT 'last delivery error',
  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'optimistic lock version',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_timeout_task_order_id` (`order_id`),
  KEY `idx_order_timeout_task_due` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reliable delayed order-close delivery tasks';

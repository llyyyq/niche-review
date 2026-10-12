-- Seckill stock warm-up outbox. The voucher row and its Redis stock cannot be
-- written atomically, so the warm-up is recorded in the same transaction as the
-- voucher and replayed by a scheduler until Redis accepts it.
CREATE TABLE IF NOT EXISTS `tb_seckill_stock_warmup_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `voucher_id` bigint unsigned NOT NULL COMMENT 'seckill voucher id',
  `stock` int unsigned NOT NULL COMMENT 'initial stock to warm up into Redis',
  `status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 pending, 1 processing, 2 completed, 3 failed',
  `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT 'write attempts',
  `next_retry_at` datetime NOT NULL COMMENT 'next write attempt',
  `processing_at` datetime DEFAULT NULL COMMENT 'claim time for timeout recovery',
  `last_error` varchar(512) DEFAULT NULL COMMENT 'last write error',
  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'optimistic lock version',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seckill_stock_warmup_voucher` (`voucher_id`),
  KEY `idx_seckill_stock_warmup_due` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transactional outbox for seckill Redis stock warm-up';

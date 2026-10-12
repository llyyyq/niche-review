-- Run once on an existing hmdp database before deploying this version.

-- A Redis-accepted order is persisted before asynchronous RocketMQ delivery.
CREATE TABLE IF NOT EXISTS `tb_voucher_order_dispatch_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `order_id` bigint unsigned NOT NULL COMMENT 'voucher order id',
  `user_id` bigint unsigned NOT NULL COMMENT 'buyer id',
  `voucher_id` bigint unsigned NOT NULL COMMENT 'voucher id',
  `status` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 pending, 1 processing, 2 sent, 3 completed, 4 cancelled',
  `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT 'delivery attempts',
  `next_retry_at` datetime NOT NULL COMMENT 'next delivery attempt',
  `processing_at` datetime DEFAULT NULL COMMENT 'claim time for timeout recovery',
  `last_error` varchar(512) DEFAULT NULL COMMENT 'last delivery error',
  `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'optimistic lock version',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_order_dispatch_order_id` (`order_id`),
  KEY `idx_voucher_order_dispatch_due` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reliable voucher-order RocketMQ dispatch outbox';

-- Redis stock is released only after cancellation commits. Existing historical
-- cancelled orders are marked complete so this migration never changes them.
ALTER TABLE `tb_voucher_order`
  ADD COLUMN `redis_stock_released` tinyint unsigned NOT NULL DEFAULT 0
  COMMENT 'whether cancelled-order Redis stock release completed' AFTER `status`;

UPDATE `tb_voucher_order`
SET `redis_stock_released` = 1
WHERE `status` = 4;

ALTER TABLE `tb_voucher_order`
  ADD KEY `idx_voucher_order_redis_release` (`status`, `redis_stock_released`, `update_time`);

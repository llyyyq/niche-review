CREATE TABLE IF NOT EXISTS `tb_ai_conversation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `user_id` bigint unsigned NOT NULL COMMENT 'conversation owner',
  `title` varchar(128) NOT NULL COMMENT 'conversation title',
  `summary` mediumtext COMMENT 'compressed history for future context',
  `summary_up_to_message_id` bigint unsigned DEFAULT NULL COMMENT 'last message included in summary',
  `last_message_at` timestamp NULL DEFAULT NULL COMMENT 'last activity time',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  KEY `idx_ai_conversation_user_last` (`user_id`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI conversations';

CREATE TABLE IF NOT EXISTS `tb_ai_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `conversation_id` bigint unsigned NOT NULL COMMENT 'conversation id',
  `user_id` bigint unsigned NOT NULL COMMENT 'conversation owner',
  `role` tinyint unsigned NOT NULL COMMENT '1 user, 2 assistant, 3 system',
  `content` mediumtext NOT NULL COMMENT 'message content',
  `status` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '0 generating, 1 completed, 2 failed',
  `input_tokens` int unsigned DEFAULT NULL COMMENT 'model input tokens',
  `output_tokens` int unsigned DEFAULT NULL COMMENT 'model output tokens',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  KEY `idx_ai_message_conversation_id_id` (`conversation_id`, `id`),
  KEY `idx_ai_message_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI conversation messages';

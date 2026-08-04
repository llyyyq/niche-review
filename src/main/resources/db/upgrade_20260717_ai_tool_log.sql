CREATE TABLE IF NOT EXISTS `tb_ai_tool_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `conversation_id` bigint unsigned DEFAULT NULL COMMENT 'conversation id; NULL for offline evaluation',
  `user_id` bigint unsigned DEFAULT NULL COMMENT 'conversation owner; NULL for offline evaluation',
  `tool_name` varchar(64) NOT NULL COMMENT 'read-only business tool name',
  `request_content` mediumtext COMMENT 'sanitized tool request summary',
  `result_content` mediumtext COMMENT 'sanitized tool result summary',
  `success` tinyint unsigned NOT NULL COMMENT '1 success, 0 failure',
  `duration_ms` bigint unsigned NOT NULL COMMENT 'tool execution duration in milliseconds',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  PRIMARY KEY (`id`),
  KEY `idx_ai_tool_log_conversation_id` (`conversation_id`, `id`),
  KEY `idx_ai_tool_log_user_id` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI read-only tool invocation logs';

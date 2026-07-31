CREATE TABLE IF NOT EXISTS `tb_ai_trace` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `request_id` varchar(32) NOT NULL COMMENT 'HTTP/SSE request correlation id',
  `trace_id` char(32) NOT NULL COMMENT 'logical AI trace id',
  `root_span_id` char(16) NOT NULL COMMENT 'root span id',
  `trace_type` varchar(32) NOT NULL COMMENT 'CHAT or SUMMARY',
  `linked_trace_id` char(32) DEFAULT NULL COMMENT 'related parent trace for detached work',
  `conversation_id` bigint unsigned DEFAULT NULL COMMENT 'conversation id',
  `user_id` bigint unsigned DEFAULT NULL COMMENT 'conversation owner',
  `user_message_id` bigint unsigned DEFAULT NULL COMMENT 'input message id',
  `assistant_message_id` bigint unsigned DEFAULT NULL COMMENT 'assistant message id',
  `status` varchar(16) NOT NULL COMMENT 'RUNNING, SUCCEEDED, FAILED or CANCELLED',
  `outcome` varchar(32) DEFAULT NULL COMMENT 'ANSWERED, CLARIFIED, NO_EVIDENCE or background result',
  `current_stage` varchar(32) DEFAULT NULL COMMENT 'latest processing stage',
  `error_stage` varchar(32) DEFAULT NULL COMMENT 'stage where the trace failed',
  `error_message` varchar(512) DEFAULT NULL COMMENT 'sanitized failure reason',
  `started_at` datetime(3) NOT NULL COMMENT 'trace start time',
  `first_token_at` datetime(3) DEFAULT NULL COMMENT 'first streamed token time',
  `completed_at` datetime(3) DEFAULT NULL COMMENT 'terminal time',
  `total_ms` bigint unsigned DEFAULT NULL COMMENT 'end-to-end duration',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_trace_request_id` (`request_id`),
  UNIQUE KEY `uk_ai_trace_trace_id` (`trace_id`),
  KEY `idx_ai_trace_conversation` (`conversation_id`, `id`),
  KEY `idx_ai_trace_assistant_message` (`assistant_message_id`),
  KEY `idx_ai_trace_status_started` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI request root traces';

CREATE TABLE IF NOT EXISTS `tb_ai_trace_span` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `trace_id` char(32) NOT NULL COMMENT 'logical AI trace id',
  `span_id` char(16) NOT NULL COMMENT 'span id',
  `parent_span_id` char(16) DEFAULT NULL COMMENT 'parent span id',
  `stage_name` varchar(32) NOT NULL COMMENT 'processing stage',
  `status` varchar(16) NOT NULL COMMENT 'RUNNING, SUCCEEDED or FAILED',
  `attributes_json` varchar(2048) DEFAULT NULL COMMENT 'allow-listed diagnostic attributes',
  `error_message` varchar(512) DEFAULT NULL COMMENT 'sanitized failure reason',
  `started_at` datetime(3) NOT NULL COMMENT 'span start time',
  `completed_at` datetime(3) DEFAULT NULL COMMENT 'span end time',
  `duration_ms` bigint unsigned DEFAULT NULL COMMENT 'span duration',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_trace_span` (`trace_id`, `span_id`),
  KEY `idx_ai_trace_span_parent` (`trace_id`, `parent_span_id`, `id`),
  KEY `idx_ai_trace_span_stage` (`stage_name`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI request trace spans';

ALTER TABLE `tb_ai_request_log`
  ADD COLUMN `request_id` varchar(32) DEFAULT NULL COMMENT 'HTTP/SSE request correlation id' AFTER `assistant_message_id`,
  ADD COLUMN `trace_id` char(32) DEFAULT NULL COMMENT 'logical AI trace id' AFTER `request_id`,
  ADD COLUMN `span_id` char(16) DEFAULT NULL COMMENT 'model-call span id' AFTER `trace_id`,
  ADD COLUMN `parent_span_id` char(16) DEFAULT NULL COMMENT 'parent span id' AFTER `span_id`,
  ADD KEY `idx_ai_request_log_request_id` (`request_id`, `id`),
  ADD KEY `idx_ai_request_log_trace_id` (`trace_id`, `id`),
  ADD KEY `idx_ai_request_log_assistant_message` (`assistant_message_id`, `id`);

ALTER TABLE `tb_ai_tool_log`
  ADD COLUMN `assistant_message_id` bigint unsigned DEFAULT NULL COMMENT 'assistant message id' AFTER `user_id`,
  ADD COLUMN `request_id` varchar(32) DEFAULT NULL COMMENT 'HTTP/SSE request correlation id' AFTER `assistant_message_id`,
  ADD COLUMN `trace_id` char(32) DEFAULT NULL COMMENT 'logical AI trace id' AFTER `request_id`,
  ADD COLUMN `span_id` char(16) DEFAULT NULL COMMENT 'tool-call span id' AFTER `trace_id`,
  ADD COLUMN `parent_span_id` char(16) DEFAULT NULL COMMENT 'parent span id' AFTER `span_id`,
  ADD COLUMN `tool_call_id` varchar(32) DEFAULT NULL COMMENT 'logical tool invocation id' AFTER `parent_span_id`,
  ADD KEY `idx_ai_tool_log_request_id` (`request_id`, `id`),
  ADD KEY `idx_ai_tool_log_trace_id` (`trace_id`, `id`),
  ADD KEY `idx_ai_tool_log_assistant_message` (`assistant_message_id`, `id`),
  ADD KEY `idx_ai_tool_log_tool_call` (`tool_call_id`);

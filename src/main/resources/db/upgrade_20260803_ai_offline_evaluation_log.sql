-- Offline RAG evaluations have a requestId/traceId but intentionally do not create a user conversation.
-- Run once for databases created before this migration.
ALTER TABLE `tb_ai_tool_log`
  MODIFY COLUMN `conversation_id` bigint unsigned DEFAULT NULL COMMENT 'conversation id; NULL for offline evaluation',
  MODIFY COLUMN `user_id` bigint unsigned DEFAULT NULL COMMENT 'conversation owner; NULL for offline evaluation';

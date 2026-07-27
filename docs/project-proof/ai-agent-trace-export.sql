-- AI Agent Trace 导出脚本
-- 使用方式：先在前端分别新建独立会话并发送两个固定问题，等待 SSE 完成；
-- 再执行本脚本。独立会话可以避免同一会话内多轮日志互相干扰。

-- Trace 1：优惠券与探店笔记查询
SELECT
    user_message.conversation_id,
    user_message.id AS user_message_id,
    assistant_message.id AS assistant_message_id,
    user_message.content AS user_question,
    assistant_message.content AS assistant_answer,
    user_message.create_time AS user_message_time,
    assistant_message.create_time AS assistant_message_time
FROM tb_ai_message user_message
LEFT JOIN tb_ai_message assistant_message
    ON assistant_message.conversation_id = user_message.conversation_id
   AND assistant_message.role = 2
   AND assistant_message.id > user_message.id
WHERE user_message.role = 1
  AND user_message.content = '103茶餐厅有什么优惠券和探店笔记？'
ORDER BY user_message.id DESC
LIMIT 1;

-- 将上一步的三列结果填入 @trace1_* 变量后再执行以下两个查询。
-- 例如：SET @trace1_conversation_id = 25;
--       SET @trace1_started_at = '2026-07-25 10:00:00';
--       SET @trace1_finished_at = '2026-07-25 10:00:05';
SET @trace1_conversation_id = 0;
SET @trace1_started_at = '2026-01-01 00:00:00';
SET @trace1_finished_at = '2026-01-01 00:00:00';
SELECT
    id, request_type, assistant_message_id, provider, model,
    retrieval_ms, tool_ms, first_token_ms, total_ms,
    input_tokens, output_tokens, success, error_message, create_time
FROM tb_ai_request_log
WHERE conversation_id = @trace1_conversation_id
  AND create_time BETWEEN @trace1_started_at AND DATE_ADD(@trace1_finished_at, INTERVAL 10 SECOND)
ORDER BY id;

SELECT
    id, tool_name, request_content, result_content,
    duration_ms, success, create_time
FROM tb_ai_tool_log
WHERE conversation_id = @trace1_conversation_id
  AND create_time BETWEEN @trace1_started_at AND DATE_ADD(@trace1_finished_at, INTERVAL 10 SECOND)
ORDER BY id;

-- Trace 2：推荐问题。该问题会命中“推荐/人均/聚餐”规划条件，通常会产生 agent_plan 记录。
SELECT
    user_message.conversation_id,
    user_message.id AS user_message_id,
    assistant_message.id AS assistant_message_id,
    user_message.content AS user_question,
    assistant_message.content AS assistant_answer,
    user_message.create_time AS user_message_time,
    assistant_message.create_time AS assistant_message_time
FROM tb_ai_message user_message
LEFT JOIN tb_ai_message assistant_message
    ON assistant_message.conversation_id = user_message.conversation_id
   AND assistant_message.role = 2
   AND assistant_message.id > user_message.id
WHERE user_message.role = 1
  AND user_message.content = '推荐适合朋友聚餐、人均 200 的餐厅，并说明理由。'
ORDER BY user_message.id DESC
LIMIT 1;

-- 将上一步的三列结果填入 @trace2_* 变量后再执行以下两个查询。
-- 例如：SET @trace2_conversation_id = 26;
--       SET @trace2_started_at = '2026-07-25 10:01:00';
--       SET @trace2_finished_at = '2026-07-25 10:01:08';
SET @trace2_conversation_id = 0;
SET @trace2_started_at = '2026-01-01 00:00:00';
SET @trace2_finished_at = '2026-01-01 00:00:00';
SELECT
    id, request_type, assistant_message_id, provider, model,
    retrieval_ms, tool_ms, first_token_ms, total_ms,
    input_tokens, output_tokens, success, error_message, create_time
FROM tb_ai_request_log
WHERE conversation_id = @trace2_conversation_id
  AND create_time BETWEEN @trace2_started_at AND DATE_ADD(@trace2_finished_at, INTERVAL 10 SECOND)
ORDER BY id;

SELECT
    id, tool_name, request_content, result_content,
    duration_ms, success, create_time
FROM tb_ai_tool_log
WHERE conversation_id = @trace2_conversation_id
  AND create_time BETWEEN @trace2_started_at AND DATE_ADD(@trace2_finished_at, INTERVAL 10 SECOND)
ORDER BY id;

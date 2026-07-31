-- AI request trace export
-- 1. Copy traceId from the SSE message_start event.
-- 2. Set @trace_id and execute this script.

SET @trace_id = 'replace-with-32-character-trace-id';

-- Root request and terminal status.
SELECT
    request_id,
    trace_id,
    trace_type,
    linked_trace_id,
    conversation_id,
    user_id,
    user_message_id,
    assistant_message_id,
    status,
    outcome,
    current_stage,
    error_stage,
    error_message,
    started_at,
    first_token_at,
    completed_at,
    total_ms
FROM tb_ai_trace
WHERE trace_id = @trace_id;

-- Exact stage timeline. No conversation/time-window approximation is used.
SELECT
    span_id,
    parent_span_id,
    stage_name,
    status,
    duration_ms,
    attributes_json,
    error_message,
    started_at,
    completed_at
FROM tb_ai_trace_span
WHERE trace_id = @trace_id
ORDER BY started_at, id;

-- Query rewrite, planner, final model and other model requests.
SELECT
    id,
    request_id,
    trace_id,
    span_id,
    parent_span_id,
    request_type,
    assistant_message_id,
    provider,
    model,
    retrieval_ms,
    tool_ms,
    first_token_ms,
    total_ms,
    input_tokens,
    output_tokens,
    success,
    error_message,
    create_time
FROM tb_ai_request_log
WHERE trace_id = @trace_id
ORDER BY id;

-- Every local tool invocation is correlated by traceId and toolCallId.
SELECT
    id,
    request_id,
    trace_id,
    span_id,
    parent_span_id,
    tool_call_id,
    assistant_message_id,
    tool_name,
    request_content,
    result_content,
    duration_ms,
    success,
    create_time
FROM tb_ai_tool_log
WHERE trace_id = @trace_id
ORDER BY id;

-- Optional: find the most recent chat trace when the SSE traceId was not saved.
SELECT
    request_id,
    trace_id,
    conversation_id,
    assistant_message_id,
    status,
    outcome,
    total_ms,
    started_at
FROM tb_ai_trace
WHERE trace_type = 'CHAT'
ORDER BY id DESC
LIMIT 10;

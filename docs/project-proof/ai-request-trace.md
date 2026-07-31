# AI 请求级 Trace

## 目标

每次 `/ai/conversations/{conversationId}/chat` 请求生成独立的 `requestId` 和
`traceId`。查询重写、检索、Agent 路由、模型规划、工具调用、最终模型、SSE
以及消息持久化都记录为同一 Trace 下的 Span。

`conversationId` 用于业务会话，`traceId` 用于一次调用链。即使同一会话并发
发送两条消息，也不会再依赖时间窗口拼接日志。

## SSE 关联

首个事件会返回：

```text
event:message_start
data:{"requestId":"...","traceId":"...","conversationId":1,"messageId":2}
```

`message_end` 和 `error` 事件也携带相同标识。复制 `traceId` 后执行
`docs/project-proof/ai-agent-trace-export.sql`，可以查询根状态、Span 时间线、
模型请求和工具调用。

## 成功链路示例

发送一个需要实时优惠券和探店笔记的问题：

```text
103茶餐厅有什么优惠券和探店笔记？
```

预期 Span：

```text
CHAT
├── SSE_STREAM
├── QUERY_PREPROCESS
├── RETRIEVAL
│   ├── EMBEDDING
│   ├── QDRANT_SEARCH
│   ├── KEYWORD_SEARCH
│   └── RESULT_MERGE
├── AGENT_ROUTE
│   ├── TOOL_CALL(shopDetail)
│   ├── TOOL_CALL(voucherQuery)
│   └── TOOL_CALL(blogSearch)
├── FINAL_MODEL
└── MESSAGE_PERSIST
```

验收条件：

- 根 Trace 为 `SUCCEEDED/ANSWERED`。
- 所有模型日志和工具日志具有相同 `trace_id`。
- 每个工具具有独立 `tool_call_id`。
- `first_token_at`、`total_ms` 与请求日志中的首 Token、总耗时可以相互核对。

## 工具失败但回答降级成功

在测试环境令一个只读工具抛出异常，再发送会触发该工具的问题。工具服务会隔离
异常并继续生成回答。

预期结果：

- 对应 `TOOL_CALL` Span 为 `FAILED`，包含脱敏错误摘要。
- `tb_ai_tool_log.success=0`，且与失败 Span 使用相同 `span_id`。
- 若其余证据足够，根 Trace 仍为 `SUCCEEDED`；最终回答不得使用失败工具未返回的事实。
- 若最终模型也失败，根 Trace 为 `FAILED`，`error_stage=FINAL_MODEL`。

## 后台摘要

会话摘要不是用户 SSE 响应的一部分，因此使用独立 `SUMMARY` Trace，并通过
`linked_trace_id` 指向原聊天 Trace。这样可以区分用户可感知耗时与响应完成后的
后台工作。

## 数据与隐私边界

- Span 只保存模式、数量、店铺 ID、模型、Token 等允许字段。
- 不在 Span 属性中保存完整问题、Prompt 或精确坐标。
- 详细工具结果沿用原工具日志，并进行长度限制和错误脱敏。
- Trace 默认保留30天，由定时任务分批清理。

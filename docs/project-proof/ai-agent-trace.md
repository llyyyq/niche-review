# AI Request Trace

每个 `/ai/conversations/{conversationId}/chat` 请求由服务端生成独立的
`requestId`、`traceId` 和根 `spanId`。`conversationId` 仅关联业务会话；同一会话的
多次并发问答也会产生不同 Trace。

## 当前工作流

```text
CHAT
├── QUERY_PREPROCESS
│   └── QUERY_REWRITE_MODEL × N
├── QUERY_PLAN_RESOLVE
├── RETRIEVAL
│   ├── STRUCTURED_RETRIEVAL × task
│   ├── EMBEDDING（仅 semanticPreference 非空）
│   ├── QDRANT_SEARCH（仅 semanticPreference 非空）
│   └── RESULT_MERGE
├── AGENT_ROUTE
│   └── TOOL_ROUTE → TOOL_CALL × N
├── FINAL_MODEL
├── SSE_STREAM
└── MESSAGE_PERSIST
```

查询计划调用 OpenAI-Compatible `response_format: {"type":"json_object"}`，要求模型只输出
一个 JSON 对象，并通过 `thinking.type=disabled` 关闭该阶段不必要的推理输出；规划器只
校验 JSON 字段类型、枚举值、任务数量和长度边界，不再用原问题规则重新推断预算、类别或
实时事实。Resolver 再将模型字段解析为数据库实体并执行检索安全检查。`TOOL_ROUTE`
由 Java 按已解析的 `requiredFacts` 确定调用 `shopDetail`、`voucherQuery`、
`blogSearch`、`nearbyShopSearch`，不会进行第二次模型工具规划。

## 候选与证据边界

- Qdrant 的店铺画像和探店笔记集合各预取最多 `10` 条，用于 Payload 过滤与按 `shopId` 去重。
- 每个任务最多保留 `5` 家符合实体、排除店铺、类别、商圈和预算约束的候选；工具调用与 Prompt 证据按任务隔离。
- 最终回答最多展示 `3` 家推荐店铺；剩余候选仅作为内部证据，不会混入其他任务。

## 故障状态

| 场景 | Trace 结果 | 下游行为 |
| --- | --- | --- |
| 指代不唯一、明确店铺超过 5 家、仅请求实时事实但无检索条件 | `CLARIFIED` | 直接 SSE 返回澄清，不调用检索、工具或最终模型。 |
| 计划模型超时、返回非法 JSON 或重试耗尽 | `FAILED` / `QUERY_PREPROCESS` | 返回服务繁忙，不执行检索。 |
| 实体、类别或商圈无法解析 | `CLARIFIED` / `QUERY_PLAN_RESOLVE` | 返回对应澄清。 |
| Embedding 或 Qdrant 不可用 | 检索 Span 标记降级 | 保留 MySQL 硬条件候选，最终回答不宣称未验证的主观偏好。 |
| 工具调用失败 | 对应 `TOOL_CALL` 失败 | 工具错误作为任务结果写入 Prompt，其余证据仍可回答。 |

## 复核方式

使用 [ai-agent-trace-export.sql](ai-agent-trace-export.sql) 传入 `traceId`，可查询根
Trace、全部 Span、模型请求日志和工具日志。Span 属性只保存阶段、数量、店铺 ID、Token
和耗时，不保存完整用户 Prompt。

# 会话级端到端 RAG 评测证据

本评测通过现有 `IAiConversationService.chat()` 运行，不调用私有检索方法，也不向数据库预写助手回答。每个案例均创建独立会话，后续轮次读取同一会话中前一轮真实生成并持久化的助手消息。

```text
冻结 turns_json
  -> 创建 [E2E-EVAL] 隔离会话
  -> 调用真实 chat() 接口
  -> 异步生成、工具调用、SSE、消息持久化
  -> 读取 requestId / traceId / Span / 工具日志
  -> 下一轮复用真实会话历史
  -> 导出结果并人工复核最终答案
```

## 评测资产

| 文件 | 用途 |
| --- | --- |
| [rag-conversation-holdout.csv](rag-conversation-holdout.csv) | 冻结的 40 条会话级留出集，包含短问题、指代、长输入、多意图、继承约束和应澄清问题。 |
| [rag-conversation-evaluation.md](rag-conversation-evaluation.md) | 评测入口、执行边界和人工复核字段说明。 |
| [rag-conversation-holdout-result.csv](rag-conversation-holdout-result.csv) | 2026-08-04 首次完整运行的原始 40 条结果。 |
| [rag-conversation-holdout-result-excel.csv](rag-conversation-holdout-result-excel.csv) | 与原始结果内容一致、带 UTF-8 BOM 的 Excel 复核副本。 |
| [rag-conversation-holdout-report.md](rag-conversation-holdout-report.md) | 首次运行的自动执行指标。 |
| [rag-conversation-holdout-result-retry.csv](rag-conversation-holdout-result-retry.csv) | 最近一次单案例重跑结果。 |
| [rag-conversation-holdout-report-retry.md](rag-conversation-holdout-report-retry.md) | 最近一次单案例重跑的自动指标。 |
| [ai-agent-trace-export.sql](ai-agent-trace-export.sql) | 输入 `traceId` 后导出根 Trace、Span、模型日志和工具日志。 |

`*-retry.*` 文件是运行器的当前重跑输出，会被下一次重跑覆盖。需要长期保存一次重跑证据时，应在下一次运行前复制并使用带日期或案例 ID 的文件名归档。

## 本次执行记录

首次完整运行共执行 40 条案例：40 条会话都完成结束，34 条根 Trace 为 `SUCCEEDED`；另外 6 条在 `FINAL_MODEL` 阶段受到上游模型服务 `HTTP 503` 影响。该错误归因为基础依赖，不归因为 Qdrant 检索、关键词 fallback 或工具调用。

下表记录了后续对这 6 条的重跑 Trace。`SUCCEEDED` 只能说明链路和模型调用正常结束；最终事实正确性仍需按照下一节人工复核。

| 案例 | 后续 Trace | 运行状态 | 说明 |
| --- | --- | --- | --- |
| CE009 | `4eec7a5dbf15cb543de90af0e9371de4` | `SUCCEEDED` | 两轮指代问答：可从“炉鱼”解析“它”，调用 `shopDetail` 后返回全天营业。 |
| CE017 | `8f4fdac79ddf5b3d5e613bc3e323e7d3` | `SUCCEEDED` | 长输入推荐案例；本次首 Token 较慢，应在延迟分析中标记为 `FINAL_MODEL` 慢调用。 |
| CE018 | `08c5cd3dffffb657c6338d63eb4c52c9` | `SUCCEEDED` | 链路成功结束，但最终答案是否满足“运河上街附近 KTV”需人工复核。 |
| CE021 | `987d8c7470aea6e6088f43d9882c776f` | `SUCCEEDED` | 同时核实店铺事实和实时优惠券。 |
| CE037 | `bff09b592c5c1d4a613c2a69fba26c9e` | `SUCCEEDED` | 继承上一轮预算和店铺上下文。 |
| CE040 | `f6f55fa8c15ca901ad5c435bf8d8c768` | `SUCCEEDED` | 继承上下文后查询预算适配性和公开笔记。 |

## 人工复核口径

自动列只验证 Trace 是否结束、所需工具是否调用、预期店铺名称是否在最终文本出现。它们不等于答案正确率。

逐条查看 `final_answer`、`tool_evidence` 和对应 `traceId`，填写以下字段：

| 字段 | 判定重点 |
| --- | --- |
| `review_faithfulness` | 地址、价格、评分、营业时间、优惠券是否能从召回资料或实时工具结果找到依据。 |
| `review_constraints` | 是否遵守预算、商圈、类别、排除条件。 |
| `review_coverage` | 多意图问题的每个子问题是否都回答。 |
| `review_realtime_consistency` | 优惠券、营业时间等是否以实时工具结果为准。 |
| `review_no_evidence_safety` | 无证据或歧义时是否拒答/澄清，且没有编造事实。 |
| `review_failure_stage` | 固定填写 `QUERY_UNDERSTANDING`、`RETRIEVAL`、`TOOL`、`GENERATION` 或 `DEPENDENCY`。 |

例如，工具和检索均正确、最终答案却把 A 店营业时间说成 B 店，应归因为 `GENERATION`；用户明确询问实时优惠券但未调用 `voucherQuery`，应归因为 `TOOL`；模型服务 503 则归因为 `DEPENDENCY`。

## Trace 复核

对某一案例，先在 CSV 中复制 `trace_id`，再使用 [ai-agent-trace-export.sql](ai-agent-trace-export.sql) 导出以下阶段：

```text
CHAT
├── QUERY_PREPROCESS
├── RETRIEVAL
│   ├── EMBEDDING
│   ├── QDRANT_SEARCH
│   ├── KEYWORD_SEARCH
│   └── RESULT_MERGE
├── AGENT_ROUTE
│   └── TOOL_CALL × N
├── FINAL_MODEL
├── SSE_STREAM
└── MESSAGE_PERSIST
```

排查顺序为：先确认 `QUERY_PREPROCESS` 的模式和候选约束，再看 `RETRIEVAL` 是否召回预期店铺、`TOOL_CALL` 是否返回实时事实，最后将 `FINAL_MODEL` 输出与证据对照。这样可以将失败归因到具体阶段，而不是笼统归为“RAG 不准”。

## 复现说明

1. 业务数据变化后，先使用 `--spring.profiles.active=local,knowledge-rebuild` 重建知识库。
2. 看到全量建库完成后，停止应用。
3. 使用 `--spring.profiles.active=local,conversation-evaluation` 运行冻结的 40 条会话案例。
4. 在下一次重跑前复制 CSV 和报告，避免 `*-retry.*` 覆盖旧证据。
5. 使用 Excel 复核副本填写人工结论，并保留对应 `traceId` 以供追溯。

评测结论应分开表述：自动指标证明链路执行与工具路由；人工复核才用于判断最终答案忠实度和约束遵守情况。

## 最终人工复核结果

40 条案例完成端到端运行和人工复核，其中 37 条最终答案通过，正确率为 `92.5%`。

| 案例 | 失败阶段 | 复核结论 |
| --- | --- | --- |
| CE018 | `RETRIEVAL` / 约束处理 | 应推荐运河上街附近 KTV，但最终回答称没有符合条件的 KTV。 |
| CE019 | `RETRIEVAL` / 约束处理 | 已有满足预算的餐厅证据，但最终回答错误返回 `NO_EVIDENCE`。 |
| CE038 | `QUERY_UNDERSTANDING` | 上一轮已明确为炉鱼，但后续“它”未解析到该店，错误返回澄清。 |

`CE009` 首次运行等待超时，重跑后完成两轮指代问答并计入通过。`CE017` 首 Token 较慢，但最终答案满足评测条件，不计为功能失败。

本次会话级评测不单独公布 Hit@1/Hit@3。当前端到端结果中的 `expected_entity_mentioned` 只表示最终文本是否提及预期店铺，不能替代向量召回 Top1/Top3 命中率；旧离线 Hit@K 结果已删除，避免混淆两个架构版本。

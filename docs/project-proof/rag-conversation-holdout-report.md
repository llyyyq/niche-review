# 会话级 RAG 端到端评测

> 生成时间：2026-08-04T10:12:42.688
> 用例源：`docs\project-proof\rag-conversation-holdout.csv`。每个用例均创建独立会话，逐轮经现有 `chat()` 执行。
> 自动检查只证明执行、Trace 和工具路由；最终事实正确性以 CSV 的 `review_*` 人工复核为准。

|指标|结果|口径|
|---|---:|---|
|会话链路完成率|100.00% (40/40)|最终消息和 CHAT Trace 均结束|
|根 Trace 成功率|85.00% (34/40)|最终轮 CHAT Trace 成功|
|实时工具路由匹配率|94.29% (33/35)|仅统计声明 required_tools 的用例|
|预期实体提及率|70.00% (28/40)|只检查提及，不等于事实正确|
|P50 / P95 总耗时|3819ms / 11724ms|最终轮 request log|
|P50 / P95 首Token|3697ms / 12524ms|最终轮 request log|

## 人工复核

填写 CSV 中 `review_faithfulness`、`review_constraints`、`review_coverage`、`review_realtime_consistency`、`review_no_evidence_safety` 与 `review_failure_stage`。

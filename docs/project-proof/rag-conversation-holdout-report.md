# 固定40条会话级 Agent/RAG 端到端评测报告

> 生成时间：2026-09-14T15:35:22.383
> 用例来源：`docs\project-proof\rag-conversation-holdout.csv`。本报告复用项目现有固定40条案例，不宣称为未见过的盲测集。每个案例创建独立会话，逐轮调用真实 `chat()`，经过 SSE、工具、消息持久化和 Trace。

## 自动指标

| 指标 | 结果 | 口径 |
|---|---:|---|
| 会话链路完成率 | 100.00% (40/40) | 最终消息与 CHAT Trace 均已结束 |
| 根 Trace 成功率 | 100.00% (40/40) | 最终轮 CHAT Trace 为 SUCCEEDED |
| 计划模型有效输出率 | 100.00% (40/40) | 仅统计实际调用计划模型的案例；以 QUERY_PREPROCESS 的 validModelOutput 为准 |
| 澄清分支正确率 | 100.00% (4/4) | 场景标记为 clarify，且结果为 CLARIFIED、无下游检索/工具/最终模型 |
| 实时工具成功率 | 82.86% (29/35) | 仅统计声明 required_tools 且对应工具成功返回的用例 |
| Trace 结构完整率 | 82.50% (33/40) | 依据案例类型检查计划、检索、工具路由、SSE 和消息持久化阶段 |
| 检索 Hit@1 | 72.22% (26/36) | 最终轮 RESULT_MERGE Top1 命中预期店铺；仅统计有明确预期店铺且 fixture 有效的用例 |
| 检索 Hit@3 | 75.00% (27/36) | 最终轮 RESULT_MERGE Top3 中任一候选命中预期店铺；与 Hit@1 使用相同分母 |
| P50 / P95 / P99 总耗时 | 3218ms / 6250ms / 8407ms | 仅统计最终轮 CHAT Trace 成功且有 request log 的样本 |
| P50 / P95 / P99 首 Token | 2590ms / 5540ms / 7655ms | 仅统计最终轮 CHAT Trace 成功且有 request log 的样本 |
| Fixture 错误数 | 0 | 预期店铺名称无法唯一映射到当前 MySQL 店铺时单独报错，不计入 Hit@K |
| 人工复核完成数 | 0/40 | 仅 PASS/FAIL 视为已复核；未填写不自动判定 |
| 人工端到端通过率 | N/A | 仅统计已填写 manual_status 的案例，需人工对照答案与证据 |

> `Hit@K` 只衡量最终检索候选是否包含预期店铺，不代表最终回答正确率。澄清题没有预期店铺，因此不计入 Hit@K；它们应通过人工复核 `review_no_evidence_safety` 判断。
> 自动指标不证明最终答案事实正确。最终答案应结合 `final_answer`、`retrieved_shop_ids`、`tool_evidence` 和 `trace_id` 人工复核。
> P99 使用 nearest-rank 计算。40 条样本下，P99 接近最大值，应与 P50/P95 一并解读。

## 逐条复核

CSV 已保留 `manual_status`、`review_faithfulness`、`review_constraints`、`review_coverage`、`review_realtime_consistency`、`review_no_evidence_safety`、`review_failure_stage` 和 `review_notes`。

排查顺序：先看 `plan_mode`/`plan_valid_output` 和 `trace_stages`，再看 `retrieved_shop_ids` 与 `retrieval_summary`，随后对照 `actual_tools`/`tool_evidence`，最后检查 `final_answer`。

## 自动失败案例

| 案例 | Trace | 阶段 | 原因 |
|---|---|---|---|
| CE007 | 5f6da1cee8ffd7bb980fbcfd43a64225 | - | Trace 阶段不完整，缺少：RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE010 | cfcb7255f375d4802ae6e3ef4cda00ab | - | Trace 阶段不完整，缺少：QUERY_PLAN_RESOLVE/RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE012 | 1769730d765489e35748dca2cc62e5e5 | - | Trace 阶段不完整，缺少：QUERY_PLAN_RESOLVE/RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE013 | 0cf22a395f3f43f0ab2c9811d11c0f41 | - | Trace 阶段不完整，缺少：QUERY_PLAN_RESOLVE/RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE015 | 94eedb6be7a23bd456327f2619786c09 | - | Trace 阶段不完整，缺少：QUERY_PLAN_RESOLVE/RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE022 | 81a289541ff3bfc1804654d641fcad45 | - | Trace 阶段不完整，缺少：RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |
| CE030 | eda8511d2f946ef725dcaf4b91361faa | - | Trace 阶段不完整，缺少：RETRIEVAL/STRUCTURED_RETRIEVAL/RESULT_MERGE/AGENT_ROUTE/TOOL_ROUTE/FINAL_MODEL |

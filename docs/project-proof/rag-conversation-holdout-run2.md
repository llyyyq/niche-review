# 会话级端到端 RAG 评测报告

> 生成时间：2026-09-12T17:34:44.568
> 用例来源：`docs\project-proof\rag-conversation-holdout.csv`。每个用例创建独立会话，逐轮调用真实 `chat()`，经过 SSE、工具、消息持久化和 Trace。

## 自动指标

| 指标 | 结果 | 口径 |
|---|---:|---|
| 会话链路完成率 | 100.00% (40/40) | 最终消息与 CHAT Trace 均已结束 |
| 根 Trace 成功率 | 12.50% (5/40) | 最终轮 CHAT Trace 为 SUCCEEDED |
| 实时工具成功率 | 2.86% (1/35) | 仅统计声明 required_tools 且对应工具成功返回的用例 |
| 检索 Hit@1 | 2.78% (1/36) | 最终轮 RESULT_MERGE Top1 命中预期店铺；仅统计有明确预期店铺且 fixture 有效的用例 |
| 检索 Hit@3 | 2.78% (1/36) | 最终轮 RESULT_MERGE Top3 中任一候选命中预期店铺；与 Hit@1 使用相同分母 |
| P50 / P95 / P99 总耗时 | 2126ms / 3108ms / 3108ms | 仅统计最终轮 CHAT Trace 成功且有 request log 的样本 |
| P50 / P95 / P99 首 Token | 2101ms / 3078ms / 3078ms | 仅统计最终轮 CHAT Trace 成功且有 request log 的样本 |
| Fixture 错误数 | 0 | 预期店铺名称无法唯一映射到当前 MySQL 店铺时单独报错，不计入 Hit@K |

> `Hit@K` 只衡量最终检索候选是否包含预期店铺，不代表最终回答正确率。澄清题没有预期店铺，因此不计入 Hit@K；它们应通过人工复核 `review_no_evidence_safety` 判断。
> P99 使用 nearest-rank 计算。40 条样本下，P99 接近最大值，应与 P50/P95 一并解读。

## 人工复核

逐条查看 CSV 的 `final_answer`、`tool_evidence` 与 `trace_id`，填写 `review_faithfulness`、`review_constraints`、`review_coverage`、`review_realtime_consistency`、`review_no_evidence_safety` 和 `review_failure_stage`。

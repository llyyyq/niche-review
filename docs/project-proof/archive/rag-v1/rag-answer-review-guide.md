# 端到端 RAG 留出集与人工复核

## 评测分层

`query-rewrite-cases.csv` 的 40 条 `TEST` 是已知失败样本构成的回归集，只用于验证修复不会回退，不能作为最终泛化指标。

`query-rewrite-answer-holdout.csv` 的 40 条 `HOLDOUT` 是冻结留出集。它覆盖：8 条短明确问题、8 条唯一指代或序号引用、8 条长噪声单意图、8 条多意图、4 条继承条件和 4 条应澄清问题。留出集生成后不得用于修改 Prompt、阈值、TopK 或关键词规则。

运行前需要重建知识库，使 MySQL、Qdrant 和评测题的事实快照一致。评测运行器会为每条案例创建独立 `requestId` 和 `traceId`，并将最终回答、召回证据、工具结果、阶段耗时和人工复核列写入 CSV。

## 运行方式

回归集：

```text
--ai.evaluation.enabled=true
--ai.evaluation.mode=answer
```

默认输出：

```text
docs/project-proof/rag-answer-regression.csv
docs/project-proof/rag-answer-regression.md
```

冻结留出集：

```text
--ai.evaluation.enabled=true
--ai.evaluation.mode=answer
--ai.evaluation.rag-answer-source-path=docs/project-proof/query-rewrite-answer-holdout.csv
--ai.evaluation.rag-answer-source-split=HOLDOUT
--ai.evaluation.rag-answer-expected-case-count=40
--ai.evaluation.rag-answer-cases-path=docs/project-proof/rag-answer-holdout.csv
--ai.evaluation.rag-answer-report-path=docs/project-proof/rag-answer-holdout.md
```

留出集只在生产行为稳定后运行一次。正式指标以该次输出和人工复核结果为准。

## 自动指标的边界

自动检查覆盖：查询模式、是否错误调用重写模型、预期实体是否出现、多意图实体覆盖、需要的实时工具是否被调用、歧义是否主动澄清、是否引用未预期的店铺，以及 Trace 是否完整。

这些指标不是最终答案准确率。检索正确但模型混淆价格、地址、营业时间，或遗漏子问题，自动实体命中仍可能通过。

## 人工复核字段

对每条 `HOLDOUT` CSV 记录填写：

| 字段 | 填写值 | 判定标准 |
|---|---|---|
| `review_status` | `PASS` / `FAIL` | 整体是否可接受 |
| `review_faithfulness` | `PASS` / `FAIL` | 所有店铺、券、地址、价格和营业时间均能在召回资料或工具结果中找到依据 |
| `review_constraints` | `PASS` / `FAIL` / `N/A` | 预算、商圈、类别、排除条件没有被忽略或篡改 |
| `review_coverage` | `PASS` / `FAIL` / `N/A` | 多意图问题的每个子问题均被回答 |
| `review_realtime_consistency` | `PASS` / `FAIL` / `N/A` | 实时券、营业时间等以 `tool_evidence` 为准，未被旧向量资料覆盖 |
| `review_no_evidence_safety` | `PASS` / `FAIL` / `N/A` | 无证据或应澄清问题没有编造推荐或事实 |
| `review_failure_stage` | 固定枚举 | `QUERY_UNDERSTANDING`、`RETRIEVAL`、`TOOL`、`GENERATION`、`DEPENDENCY` |
| `review_notes` | 简短说明 | 写明证据和失败原因 |

复核顺序：先看 `trace_id` 的 `QUERY_PREPROCESS`、`RETRIEVAL`、`AGENT_ROUTE` 和 `TOOL_CALL` Span；再对照 `retrieved_evidence`、`tool_evidence` 与 `final_answer`。若证据正确、工具正确、答案错误，归因为 `GENERATION`；若正确店铺未进候选，归因为 `RETRIEVAL`；若应查实时数据但工具没调用，归因为 `TOOL`。

## 结果表述

留出集的自动结果只能描述为“端到端结构化指标”。人工复核完成后，才可额外报告人工端到端通过率；不得把 Hit@K 或实体覆盖率直接写成模型回答准确率。

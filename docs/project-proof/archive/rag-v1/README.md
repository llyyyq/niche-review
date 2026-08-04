# 第一版 RAG 评测归档

本目录保存项目早期 RAG、查询重写和答案评测材料，仅用于记录方案演进和历史问题，不作为当前项目的最终效果指标。

## 当前结果

当前版本的正式结果见：

- [会话级端到端 RAG 评测证据](../../rag-conversation-evaluation-evidence.md)
- [冻结会话评测集](../../rag-conversation-holdout.csv)
- [当前 Trace 示例](../../ai-agent-trace.md)

当前结论是：40 条冻结多轮会话经过真实 `chat()`、SSE、工具调用和人工事实复核后，37 条通过，最终答案正确率为 **92.5%**。旧版 Hit@K 数字不再用于 README 或简历。

## 归档内容

| 文件 | 说明 |
| --- | --- |
| `rag-answer-*` | 第一版答案评测及其人工复核说明，未使用当前会话级端到端口径。 |
| `query-rewrite-answer-holdout.csv` | 第一版查询重写答案评测数据。 |
| `rag-conversation-holdout-reviewed.csv` | 当前会话评测早期人工复核副本，已被最终 37/40 复核结论替代。 |
| `rag-evaluation.md`、`rag-cases.csv` | 更早的离线检索评测文件，已从主目录删除；仅保留在 Git 历史中。 |
| `query-rewrite-evaluation.md`、`query-rewrite-cases.csv` | 更早的查询重写组件评测文件，已从主目录删除；仅保留在 Git 历史中。 |

## 使用边界

归档结果可以用于回答“项目如何从第一版演进到当前版本”，但不能与当前端到端结果相加、平均或混写。当前 README 只引用主目录中的最终评测证据。

# 会话级 RAG 端到端评测

项目保留两类评测，结论不能混用：

| 类型 | 调用入口 | 上下文来源 | 验证范围 |
|---|---|---|---|
| 编排回归评测 | `IAiRagEvaluationService.evaluate()` | CSV 静态 `history_json` | 查询预处理、检索、工具路由和 Prompt 组装回归 |
| 会话级端到端评测 | `IAiConversationService.chat()` | 当前 Agent 在上一轮真实生成并持久化的消息 | 会话上下文、异步生成、工具、SSE、消息持久化和 Trace |

会话级评测不调用任何私有 Agent 组件，也不会把预写好的助手话术注入数据库。

```text
冻结 turns_json
  -> 创建 [E2E-EVAL] 隔离会话
  -> 每个用户 turn 调用现有 chat()
  -> 等待助手消息由 GENERATING 结束
  -> 读取该 turn 的 CHAT Trace / request log / tool log
  -> 下一 turn 使用真实已落库的历史
  -> 导出 CSV 并进行人工事实复核
```

## 运行

确认 MySQL、Redis、Qdrant、Embedding 服务与聊天模型都可用，且知识库已经按当前数据构建。

如果业务数据在上次建库后发生变化，先单独使用以下启动参数启动一次：

```text
--spring.profiles.active=local,knowledge-rebuild
```

看到知识库重建完成后停止应用，再使用以下启动参数运行评测：

```text
--spring.profiles.active=local,conversation-evaluation
```

运行器会创建 40 个标题以 `[E2E-EVAL]` 开头的隔离会话，生成：

```text
docs/project-proof/rag-conversation-holdout-result.csv
docs/project-proof/rag-conversation-holdout-report.md
```

这份留出集在运行前冻结；不要根据结果修改它或同一批题反复挑选更好结果。

`conversation-evaluation` 默认使用本地演示用户 `1012`。只有该用户在你的数据库不存在时，才额外设置一次非敏感环境变量：`AI_EVALUATION_USER_ID=实际用户ID`。

## 人工复核

自动列只能验证 Agent 实际执行、Trace 完整、工具路由以及预期实体提及，不能证明最终事实完全正确。逐条填写结果 CSV 的以下字段：

- `review_faithfulness`：优惠券、价格、地址、营业时间是否来自召回证据或实时工具。
- `review_constraints`：预算、类别、商圈、排除条件是否遵守。
- `review_coverage`：多问题是否完整覆盖。
- `review_realtime_consistency`：实时工具与向量资料冲突时是否以工具为准。
- `review_no_evidence_safety`：无证据时是否拒答且没有编造。
- `review_failure_stage`：查询理解、检索、工具、生成或基础依赖。

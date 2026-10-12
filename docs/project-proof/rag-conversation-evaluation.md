# 固定40条会话级 Agent/RAG 端到端评测

评测复用项目现有的40条固定案例，仅调用 `IAiConversationService.chat()`，由当前 Agent 在上一轮真实生成并持久化消息后继续下一轮。它不调用私有 Agent 组件，也不会把预写好的助手话术注入数据库，因此评测的是当前生产 Agent 的真实会话链路，而不是离线拼接结果。

```text
冻结的 turns_json
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

这40条案例是项目已有固定回归/演示集，不是未见过的盲测集。运行前应确认案例文件和业务数据版本，运行后不要根据结果修改同一批题并将其表述为泛化效果。

输出 CSV 使用 UTF-8 BOM，可直接用 Excel 打开。每一行对应一个案例的最终用户轮次；多轮案例的前置轮次仍通过真实 `chat()` 生成并落库，只把最后一轮作为该案例的目标答案进行统计。

`conversation-evaluation` 默认使用本地演示用户 `1012`。只有该用户在你的数据库不存在时，才额外设置一次非敏感环境变量：`AI_EVALUATION_USER_ID=实际用户ID`。

## 人工复核

自动列只能验证 Agent 实际执行、Trace 完整、工具路由、检索候选和预期实体提及，不能证明最终事实完全正确。逐条填写结果 CSV 的以下字段：

此外，CSV 还会记录 `plan_mode`、`plan_model_called`、`plan_valid_output`、`plan_task_count`、`trace_stages`、`trace_structure_valid`、`structured_candidate_count` 和 `qdrant_candidate_count`，用于判断结构化计划、检索阶段和请求级 Trace 是否真正执行。

`manual_status` 只填写 `PASS` 或 `FAIL` 后才会进入人工通过率统计，留空保持 `PENDING`。

- `review_faithfulness`：优惠券、价格、地址、营业时间是否来自召回证据或实时工具。
- `review_constraints`：预算、类别、商圈、排除条件是否遵守。
- `review_coverage`：多问题是否完整覆盖。
- `review_realtime_consistency`：实时工具与向量资料冲突时是否以工具为准。
- `review_no_evidence_safety`：无证据时是否拒答且没有编造。
- `review_failure_stage`：查询理解、检索、工具、生成或基础依赖。

## 完成判断

等待日志出现 `Conversation-level Agent evaluation completed, cases=40`，再读取 `docs/project-proof/rag-conversation-holdout-result.csv` 和 `docs/project-proof/rag-conversation-holdout-report.md`。业务数据变化后才需要先运行 `knowledge-rebuild`；数据未变化时直接使用 `conversation-evaluation` 即可。重跑前先复制结果文件，避免覆盖上一轮证据。

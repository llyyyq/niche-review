# 知识库最终一致性故障 Trace

## 验证目标

验证业务数据已经提交到 MySQL、但 Qdrant 临时不可用时，同步任务不会丢失；Qdrant 恢复后，任务会按重试策略完成向量更新，AI 最终能检索到最新探店笔记。

## 真实执行记录

- 执行日期：`2026-07-23`
- 关联店铺：`shop_id = 1`，即 103 茶餐厅
- 故障方式：在 Ubuntu Docker 主机执行 `docker stop qdrant`
- 变更方式：通过应用新增一条关于“向量数据库关闭情况下异步提交”的临时公开探店笔记
- 同步任务：`tb_ai_knowledge_sync_task.id = 1`
- 临时博客 ID：未在本次截图中记录；笔记标题和内容已由后续 AI 检索验证

> `tb_ai_knowledge_sync_task` 对 `shop_id` 有唯一索引。因此本次不是新增多条任务，而是复用店铺 1 的同一条任务，并将其重新置为待同步状态。这符合当前任务表设计。

## 观察到的状态变化

| 阶段 | 状态 | retry_count | 关键字段与结论 |
| --- | --- | ---: | --- |
| 变更前 | 无任务记录 | - | 查询店铺 1 时没有同步任务，说明本次变更会创建任务。 |
| Qdrant 停止后的第一次观察 | `PENDING` | `2` | `last_error` 非空，`next_retry_at` 有值，证明同步失败后任务没有丢失。 |
| 后续重试 | `PENDING` | `3` | 任务继续等待下一次重试，而不是直接进入最终失败。 |
| Qdrant 恢复后 | `SUCCEEDED` | `5` | `next_retry_at`、`processing_at`、`last_error` 均为 `NULL`，向量同步最终成功。 |

`retry_count = 5` 表示任务一共被领取并尝试执行了五次，不表示“五次都失败”。本次最后一次尝试在 Qdrant 恢复后成功。

## 故障根因与处理链路

故障期间，后端日志出现：

```text
java.net.ConnectException: Connection refused
...
at com.hmdp.ai.QdrantKnowledgeClient.request(...)
```

该异常符合 Qdrant 已被主动停止的预期。`AiKnowledgeSyncTaskServiceImpl` 捕获异常后执行以下状态流转：

```text
MySQL 博客写入成功
  -> Spring 事务提交后发布 ShopKnowledgeChangedEvent
  -> upsert 同步任务为 PENDING
  -> 尝试写入 Qdrant 失败（Connection refused）
  -> retry_count 加一，记录 last_error 和 next_retry_at
  -> 定时扫描再次 CAS 抢占任务
  -> Qdrant 恢复后同步成功
  -> 状态更新为 SUCCEEDED，并清空 last_error
```

当前默认退避参数为：首次失败后约 `10` 秒重试，后续按指数退避延长；最多尝试 `5` 次。

## 恢复后的业务验证

恢复 Qdrant 后，向 AI 提问：

```text
请问 103 茶餐厅有一个关于向量数据库测试的笔记吗？
```

AI 返回了新笔记的事实：103 茶餐厅存在一条公开笔记，标题包含“测试当前向量数据库关闭情况下异步提交到向量数据库失败解决测试”，摘要内容为“111”。

这说明恢复后的向量索引已包含 MySQL 中新增的博客内容，RAG 可以召回该变更。

## 使用的核对 SQL

```sql
SELECT
    id,
    shop_id,
    CASE status
        WHEN 0 THEN 'PENDING'
        WHEN 1 THEN 'PROCESSING'
        WHEN 2 THEN 'SUCCEEDED'
        WHEN 3 THEN 'FAILED'
    END AS status_name,
    retry_count,
    next_retry_at,
    processing_at,
    last_error,
    version,
    create_time,
    update_time
FROM tb_ai_knowledge_sync_task
WHERE shop_id = 1;
```

## 清理说明

临时博客在 MySQL 删除后，直接删除不会触发 Spring 领域事件。为了从 Qdrant 清除该博客对应内容，应临时设置：

```text
AI_KNOWLEDGE_REBUILD_ON_START=true
```

重启应用并等待 `Shop knowledge rebuild completed` 日志后，Qdrant 会按当前 MySQL 数据重建。随后移除该环境变量并正常启动。

## 结论

本次演练证明：MySQL 提交成功但 Qdrant 暂时不可用时，知识库不会长期漏更新。同步任务通过 MySQL 持久化、CAS 抢占、指数退避和超时恢复实现最终一致性；Qdrant 恢复后，新增博客可被 AI 正常检索。

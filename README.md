# Niche Review

一个面向本地生活场景的 Java 后端项目，基于黑马点评业务扩展了 Redis 缓存治理、高并发秒杀、RocketMQ 可靠异步订单，以及 Qdrant RAG 智能导购。

项目重点不是“接入一个聊天接口”，而是将本地生活数据、检索、只读业务工具、会话记忆、SSE 流式输出和可观测日志串成可追溯的 AI 导购链路。

## 项目亮点

- **完整的 Redis 缓存治理**：结合 BloomFilter、空值缓存、互斥锁、随机 TTL 与事务提交后删缓存，覆盖缓存穿透、击穿、雪崩及更新一致性问题。
- **可靠的秒杀交易链路**：Lua 原子预扣、RocketMQ 异步落库、消费幂等、DLQ 补偿与持久化超时任务共同处理高并发下单的失败边界。
- **支付与关单并发控制**：订单级 Redisson 锁配合状态条件更新，保证支付回调和超时关单竞争时订单状态不反转、库存不重复归还。
- **可评测的本地生活 RAG**：融合 Qdrant 向量召回、关键词 fallback 与业务约束，并通过固定数据集对纯向量和混合检索进行对比。
- **可恢复、可追踪的 AI 链路**：知识同步任务支持 CAS 抢占、指数退避和超时恢复；AI 请求记录检索、工具、SSE、耗时与 Token 数据。

## 快速导航

| 入口 | 内容 |
| --- | --- |
| [Architecture](#architecture) | 店铺缓存重建与删除、秒杀异步下单、超时关单、RAG、知识库同步五条核心架构。 |
| [Evaluation](#evaluation) | Spring 集成测试、JMeter 压测对账、50 条 RAG 固定评测及指标口径。 |
| [Trace](#trace) | AI 请求、检索、工具、SSE、耗时及中断恢复记录。 |
| [Failure Recovery](#failure-recovery) | MQ 发送失败、DLQ 补偿、支付与关单竞争、Qdrant 中断恢复。 |
| [Reproduce](#reproduce) | 本地依赖、数据库升级、环境变量、启动和验证命令。 |

<a id="implementation-index"></a>

## 核心设计与实现索引

| 核心设计 | 主要实现 | 测试与运行记录 |
| --- | --- | --- |
| Redis 缓存穿透、击穿、雪崩与事务后删缓存 | [`ShopServiceImpl`](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)、[`SimpleRedisLock`](src/main/java/com/hmdp/utils/SimpleRedisLock.java)、[`unlock.lua`](src/main/resources/unlock.lua) | [`ReliabilityIntegrationTest`](src/test/java/com/hmdp/reliability/ReliabilityIntegrationTest.java)、[缓存可靠性验证](docs/project-proof/reliability-verification.md) |
| Lua 预扣、RocketMQ 异步下单、幂等与 DLQ 补偿 | [`VoucherOrderServiceImpl`](src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)、[`VoucherOrderConsumer`](src/main/java/com/hmdp/mq/VoucherOrderConsumer.java)、[`VoucherOrderDeadLetterConsumer`](src/main/java/com/hmdp/mq/VoucherOrderDeadLetterConsumer.java)、[`seckill.lua`](src/main/resources/seckill.lua)、[`seckill_rollback.lua`](src/main/resources/seckill_rollback.lua) | [秒杀可靠性验证](docs/project-proof/reliability-verification.md)、[压测记录](docs/project-proof/seckill-pressure-test.md)、[对账 SQL](docs/project-proof/seckill-verify.sql) |
| 持久化超时任务、延迟消息与支付/关单竞争 | [`OrderTimeoutTaskServiceImpl`](src/main/java/com/hmdp/service/impl/OrderTimeoutTaskServiceImpl.java)、[`OrderTimeoutListener`](src/main/java/com/hmdp/mq/OrderTimeoutListener.java)、[`PaymentServiceImpl`](src/main/java/com/hmdp/service/impl/PaymentServiceImpl.java) | [支付与超时竞争验证](docs/project-proof/payment-timeout-race.md) |
| Qdrant RAG、关键词 fallback 与结构化约束 | [`ShopKnowledgeServiceImpl`](src/main/java/com/hmdp/service/impl/ShopKnowledgeServiceImpl.java)、[`QdrantKnowledgeClient`](src/main/java/com/hmdp/ai/QdrantKnowledgeClient.java)、[`RagEvaluationRunner`](src/main/java/com/hmdp/evidence/RagEvaluationRunner.java) | [RAG 评测报告](docs/project-proof/rag-evaluation.md)、[50 条案例集](docs/project-proof/rag-cases.csv) |
| 知识库增量同步、CAS 抢占与失败恢复 | [`ShopKnowledgeChangedListener`](src/main/java/com/hmdp/event/ShopKnowledgeChangedListener.java)、[`AiKnowledgeSyncTaskServiceImpl`](src/main/java/com/hmdp/service/impl/AiKnowledgeSyncTaskServiceImpl.java) | [知识库故障恢复 Trace](docs/project-proof/knowledge-sync-failure.md) |
| 只读业务工具、SSE 与调用观测 | [`AiConversationServiceImpl`](src/main/java/com/hmdp/service/impl/AiConversationServiceImpl.java)、[`AiReadOnlyToolServiceImpl`](src/main/java/com/hmdp/service/impl/AiReadOnlyToolServiceImpl.java)、[`AiAgentRunner`](src/main/java/com/hmdp/ai/AiAgentRunner.java) | [AI 调用 Trace](docs/project-proof/ai-agent-trace.md)、[Trace 导出 SQL](docs/project-proof/ai-agent-trace-export.sql) |

## 业务功能

### 本地生活业务

- 手机验证码登录、Token 刷新、登出与 `ThreadLocal` 用户上下文。
- 店铺分类、店铺详情、关键词查询、Redis GEO 附近店铺查询。
- 博客发布、点赞、关注、共同关注和基于 Redis ZSet 的 Feed 流滚动分页。
- Redis Bitmap 签到与连续签到统计。

### Redis 缓存与限流

- Redisson BloomFilter + 空值缓存防止店铺详情缓存穿透。
- 互斥锁缓存重建防止热点 Key 缓存击穿；锁释放通过 Lua 比较持有者标识，避免误删其他线程的锁。
- 随机 TTL 分散缓存失效时间，降低缓存雪崩风险。
- AOP + Redis ZSet + Lua 实现按用户/IP 维度的滑动窗口限流。

### 高并发秒杀与订单可靠性

- Lua 原子校验库存和一人一单，并预扣 Redis 库存。
- RocketMQ 异步创建订单；MySQL 唯一索引、订单 ID 幂等和用户券幂等共同防止重复下单。
- 生产发送失败和消费者最终失败均通过 Lua 幂等补偿 Redis 预扣库存及购买标记。
- 消费者最多重试 5 次；失败消息进入 DLQ 后由补偿消费者处理。
- 订单创建事务内写入 `tb_order_timeout_task`，定时投递 RocketMQ 延迟关单消息；投递失败采用指数退避重试。
- 支付回调与超时关单使用同一把 Redisson 订单锁，并配合订单状态 CAS，避免状态反转和库存重复回滚。

### AI 导购与 RAG

- Qdrant 向量知识库聚合店铺画像、有效优惠券和公开探店笔记。
- 用户问题依次经历向量化、TopK 召回、低置信度关键词 fallback、去重排序和 Prompt 注入。
- 对明确业务问题无可靠资料时返回无证据提示，避免把无关店铺交给模型编造答案。
- 有限步 Think-Execute 编排：当前只读工具白名单为店铺详情、附近店铺、优惠券和探店笔记；明确意图走快速工具路由，复杂选择最多进行一轮规划。
- 会话、消息、工具日志、请求耗时与 Token 使用量持久化；摘要和历史窗口控制上下文长度。
- 使用 Spring MVC `SseEmitter` 输出 `message_start`、`delta`、`message_end` 事件，支持 Mock 与 OpenAI-Compatible 模型。
- 店铺、优惠券、博客变更后创建 MySQL 同步任务；通过 CAS 抢占、指数退避和处理超时恢复保证 Qdrant 最终一致性。

## 技术栈

| 分类 | 组件 |
| --- | --- |
| 基础框架 | Java 8、Spring Boot 2.3.12、Spring MVC、AOP |
| 数据访问 | MyBatis-Plus、MySQL 8.x |
| Redis | Spring Data Redis、Redisson、Lua、BloomFilter、GEO、ZSet、Bitmap |
| 消息与并发 | RocketMQ、Redisson 分布式锁 |
| AI | Qdrant、OpenAI-Compatible Chat / Embedding API、SSE |
| 工程工具 | Maven、Docker、Nginx、JMeter |

<a id="architecture"></a>

## Architecture：核心架构

| 架构 | 解决的问题 |
| --- | --- |
| [店铺缓存重建与删除](#cache-architecture) | 缓存穿透、热点 Key 击穿、锁误删、集中失效及事务提交前删缓存。 |
| [秒杀异步下单](#seckill-architecture) | Redis 原子预扣、异步落库、重复消费和最终消费失败。 |
| [可靠超时关单](#timeout-architecture) | 延迟消息投递可靠性，以及支付回调与超时关单的并发竞争。 |
| [RAG 与 AI 导购](#rag-architecture) | 混合检索、无证据拒答、只读实时工具和 SSE 输出。 |
| [知识库增量同步](#knowledge-sync-architecture) | MySQL 业务数据与 Qdrant 向量文档的失败重试和最终恢复。 |

```text
Web / Nginx
    |
Spring Boot API (8081)
    |-- MySQL: 业务数据、AI 会话/日志、知识库同步任务、超时任务
    |-- Redis: 登录态、缓存、GEO、Feed、限流、秒杀预扣、分布式锁
    |-- RocketMQ: 秒杀订单、延迟关单、DLQ 补偿
    `-- AI 导购
          |-- Embedding API -> Qdrant 向量检索
          |-- 关键词 fallback / 业务约束排序
          |-- 只读业务工具
          `-- Chat API -> SSE 流式响应
```

<a id="cache-architecture"></a>

### 店铺缓存重建与删除

```text
店铺详情请求
  -> BloomFilter 判断店铺 ID 是否可能存在
  -> 查询 cache:shop:{shopId}
       |-- 命中 JSON：直接返回
       |-- 命中空值：返回店铺不存在
       `-- 未命中：尝试获取带 TTL 的 SETNX 互斥锁
             |-- 失败：短暂等待后重试
             `-- 成功：再次检查缓存
                   -> 查询 MySQL
                   -> 不存在：写入短 TTL 空值
                   -> 存在：写入带随机 TTL 的店铺 JSON
                   -> Lua 比较持有者标识后释放锁

店铺更新
  -> MySQL 事务更新
  -> 提交成功：afterCommit 删除 cache:shop:{shopId}
  -> 事务回滚：保留原缓存，不发布知识变更
```

关键边界已由集成测试覆盖：事务回滚不删缓存、事务提交后再删缓存、旧锁持有者不能删除其他线程重新获得的锁。实现与运行记录见[核心设计与实现索引](#implementation-index)和[缓存可靠性验证](docs/project-proof/reliability-verification.md)。

## 项目结构

```text
.
├── frontend/
│   ├── app/                          # 可直接部署的静态前端（HTML/CSS/JS/基础图片）
│   └── nginx/nginx.conf.example      # Nginx 反向代理示例，不包含 Nginx 二进制
├── docs/project-proof/               # RAG、可靠性和 AI Trace 验证材料
├── src/main/java/com/hmdp/
│   ├── ai/                           # Qdrant、Embedding、Chat 客户端
│   ├── annotation/ aspect/           # 限流注解与 AOP
│   ├── config/                       # Web、Redis、Redisson、AI、调度配置
│   ├── controller/                   # HTTP / SSE 接口
│   ├── mq/                           # 订单、超时、DLQ 消费者
│   ├── service/impl/                 # 业务、RAG、会话、同步任务实现
│   └── utils/                        # Redis Key、Lua、锁和常量
└── src/main/resources/
    ├── db/                           # 初始化 SQL 与增量升级脚本
    ├── *.lua                         # 秒杀、回滚、限流、解锁脚本
    └── application.yaml              # 不含真实凭据的示例配置
```

<a id="reproduce"></a>

## Reproduce：运行与复现

### 1. 前置环境

- JDK 8
- Maven 3.8+
- MySQL 8.x
- Redis 6+
- RocketMQ NameServer 与 Broker
- Docker（运行 Qdrant 时需要）
- 可选：Nginx（承载静态前端并反向代理 `/api`）

### 2. 初始化数据库

创建数据库并导入基础表结构：

```sql
CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

执行：

```text
src/main/resources/db/hmdp.sql
```

若是在已存在的旧数据库上升级，请按时间顺序执行 `src/main/resources/db/` 下的 `upgrade_*.sql`，至少包括：

```text
upgrade_20260629_voucher_order_idempotent.sql
upgrade_20260717_ai_conversation.sql
upgrade_20260717_ai_tool_log.sql
upgrade_20260718_ai_knowledge_sync_task.sql
upgrade_20260718_ai_memory_observability.sql
upgrade_20260723_order_timeout_task.sql
```

### 3. 配置本地 Profile

复制并填写 `src/main/resources/application-local.yaml`。该文件已被 Git 忽略，不应提交密码、IP、模型 API Key 或 Qdrant API Key。

最低业务配置示例：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: your-username
    password: your-password
  redis:
    host: 127.0.0.1
    port: 6379
    password: your-password

rocketmq:
  name-server: 127.0.0.1:9876
```

本地启动参数：

```text
--spring.profiles.active=local
```

### 4. 启动 Qdrant 和配置 AI 环境变量

Qdrant 示例：

```bash
docker run -d --name qdrant --restart unless-stopped \
  -p 6333:6333 \
  -e QDRANT__SERVICE__API_KEY=your-qdrant-api-key \
  -v qdrant-storage:/qdrant/storage \
  qdrant/qdrant:v1.18.2
```

在 IntelliJ Run Configuration 的 Environment variables 中配置真实值。以下是变量名，具体模型名和服务地址按供应商文档填写：

```text
AI_CHAT_PROVIDER=openai-compatible
AI_CHAT_BASE_URL=https://your-provider/v1/chat/completions
AI_CHAT_MODEL=your-chat-model
AI_CHAT_API_KEY=your-chat-api-key

AI_EMBEDDING_PROVIDER=openai-compatible
AI_EMBEDDING_BASE_URL=https://your-provider/v1/embeddings
AI_EMBEDDING_MODEL=your-embedding-model
AI_EMBEDDING_API_KEY=your-embedding-api-key

AI_QDRANT_URL=http://127.0.0.1:6333
AI_QDRANT_API_KEY=your-qdrant-api-key
```

首次构建或需要全量重建知识库时，额外设置一次：

```text
AI_KNOWLEDGE_REBUILD_ON_START=true
```

看到 `Shop knowledge rebuild completed` 后移除该变量，后续业务变更会使用增量同步。

### 5. 启动后端和前端

```bash
mvn spring-boot:run
```

后端默认地址：`http://localhost:8081`。

若通过 Nginx 承载前端，可将 `/api` 代理到后端：

```nginx
location /api {
    rewrite /api(/.*) $1 break;
    proxy_pass http://127.0.0.1:8081;
}
```

前端静态资源位于 `frontend/app`。将该目录中的内容复制到本机 Nginx 的 `html/hmdp`，再参考 `frontend/nginx/nginx.conf.example` 配置 Nginx；不要将 `nginx.exe`、日志、临时目录或运行时上传的博客图片提交到仓库。

### 6. 复现测试与证据

缓存与消息幂等集成测试：

```bash
mvn -q -Dtest=ReliabilityIntegrationTest test
```

生成 JMeter 使用的 10,000 个不同用户 Token：

```bash
mvn -q -Dtest=HmDianPingApplicationTests#prepareJmeterTokens test
```

生成的 `tokens.csv` 含真实登录 Token，已被 `.gitignore` 排除。JMeter 线程、Ramp-Up、断言口径及对账步骤见[秒杀压测记录](docs/project-proof/seckill-pressure-test.md)。

重新运行 50 条 RAG 评测时，在已经配置真实 Embedding 与 Qdrant 的本地环境中设置：

```text
AI_EVALUATION_ENABLED=true
```

随后启动应用。`RagEvaluationRunner` 会分别执行纯向量和混合检索，并更新 [`rag-cases.csv`](docs/project-proof/rag-cases.csv)；关闭该环境变量后再正常启动业务服务。

AI 对话执行完成后，可使用 [`ai-agent-trace-export.sql`](docs/project-proof/ai-agent-trace-export.sql) 导出用户消息、助手消息、请求日志和工具日志，形成完整的请求链路记录。

## 关键接口

| 场景 | 方法与路径 |
| --- | --- |
| 秒杀下单 | `POST /voucher-order/seckill/{voucherId}` |
| 模拟支付 | `POST /payment/simulate/{orderId}` |
| 创建 AI 会话 | `POST /ai/conversations` |
| 查询会话列表 | `GET /ai/conversations?current=1` |
| 查询会话消息 | `GET /ai/conversations/{conversationId}/messages` |
| 重命名会话 | `PATCH /ai/conversations/{conversationId}` |
| 删除会话 | `DELETE /ai/conversations/{conversationId}` |
| SSE AI 对话 | `POST /ai/conversations/{conversationId}/chat` |

受登录保护的接口需要请求头：

```http
Authorization: {token}
```

SSE 调用示例：

```bash
curl.exe -N -X POST "http://localhost:8081/ai/conversations/1/chat" \
  -H "Authorization: your-token" \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"推荐适合朋友聚餐、人均 200 的餐厅\"}"
```

<a id="seckill-architecture"></a>

### 秒杀异步下单

```text
用户请求
  -> 限流
  -> seckill.lua：库存 + 一人一单 + Redis 预扣
  -> 同步发送 RocketMQ 订单消息
       |-- 发送失败：seckill_rollback.lua 回滚 Redis 预扣
       `-- 发送成功：立即返回 orderId

订单消费者
  -> orderId / userId+voucherId 幂等校验
  -> 扣减 MySQL 库存、保存未支付订单、创建超时任务（同一事务）
  -> 失败时 RocketMQ 最多重试 5 次
  -> 最终失败进入 DLQ，幂等释放 Redis 预扣
```

生产者发送失败和 DLQ 最终补偿都调用 `seckill_rollback.lua`。脚本以 `seckill:reservation:{orderId}` 作为一次性预占标记，只有首次成功删除该标记时才移除用户购买记录并归还 Redis 库存，避免重复补偿导致库存多加。

<a id="timeout-architecture"></a>

### 可靠超时关单

```text
订单消费者事务
  -> 扣减 MySQL 库存
  -> 保存未支付订单
  -> 写入 tb_order_timeout_task（与订单同一事务）

超时任务投递器
  -> CAS 抢占 PENDING 任务
  -> 根据订单剩余支付时间发送 RocketMQ 延迟消息
       |-- 成功：标记 SENT
       `-- 失败：指数退避后重新进入 PENDING

支付回调 / 延迟关单
  -> 同一把 Redisson order lock
  -> 基于订单状态条件更新
       |-- 支付先到：UNPAID -> PAID，后续关单消息安全忽略
       `-- 关单先到：UNPAID -> CLOSED，仅首次归还库存
```

完整竞争验证见[支付与超时竞争](docs/project-proof/payment-timeout-race.md)。

<a id="rag-architecture"></a>

### RAG 与 AI 导购

```text
用户问题
  -> 持久化用户消息
  -> Embedding 向量化
  -> Qdrant TopK 向量召回
  -> 低置信度时关键词 fallback + 结构化约束排序
  -> 无可靠资料：直接返回无证据提示
  -> 有资料：快速工具路由或最多一轮 agent_plan
  -> 店铺 / 附近 / 优惠券 / 博客只读工具
  -> 组装上下文与 Prompt
  -> 外部模型 SSE 流式输出
  -> 持久化助手消息、会话摘要、请求日志和工具日志
```

相关数据表包括：

```text
tb_ai_conversation
tb_ai_message
tb_ai_request_log
tb_ai_tool_log
tb_ai_knowledge_sync_task
```

<a id="knowledge-sync-architecture"></a>

### 知识库增量同步

```text
店铺 / 优惠券 / 博客发生业务变更
  -> 业务事务提交
  -> AFTER_COMMIT 事件监听器异步创建 / 合并同步任务
  -> tb_ai_knowledge_sync_task = PENDING
  -> Worker 按 taskId + version CAS 抢占为 PROCESSING
       |-- 成功写入 Qdrant：标记 SUCCEEDED
       `-- 调用失败：记录 last_error，指数退避后重回 PENDING
  -> 定时扫描恢复到期任务及超时 PROCESSING 任务
```

任务表以 `shop_id` 聚合同一店铺的连续变更，版本号 CAS 防止并发 Worker 重复处理；已经落库的任务在 Qdrant 中断后可以持续重试。真实的 `PENDING（retry_count 递增）-> SUCCEEDED` 过程见[知识库故障恢复 Trace](docs/project-proof/knowledge-sync-failure.md)。

> 边界：同步任务在业务事务提交后由异步监听器创建，因此“业务已提交、任务尚未落库”仍存在极小的进程崩溃窗口；当前恢复机制保证的是**已持久化同步任务**不会因临时故障长期丢失。

<a id="evaluation"></a>

## Evaluation：测试与评测

| 评测项 | 固定口径 | 结果 |
| --- | --- | --- |
| Spring 集成测试 | 缓存事务后删除、过期锁误删防护、同一订单消息重复消费 | `3/3` 通过，`Failures=0`，`Errors=0` |
| 秒杀压测 | 10,000 个不同 Token、10,000 线程、Ramp-Up 30 秒、库存 100 | 平均吞吐 `333.2 req/s`；MySQL 订单数与唯一用户数均为 `100`；MySQL/Redis 库存均为 `0` |
| RAG 固定评测 | 43 条事实类问题 + 7 条无结果问题 | Hit@1：`88.37% -> 97.67%`；Hit@3：`93.02% -> 100%`；无结果正确拒答 `7/7` |

| 文档 | 内容 |
| --- | --- |
| [RAG 评测](docs/project-proof/rag-evaluation.md) | 50 条真实案例；43 条可命中题中混合检索 Hit@1 为 97.67%，Hit@3 为 100.00%；7 条无结果题均空召回且正确拒答。 |
| [RAG 案例集](docs/project-proof/rag-cases.csv) | 每题的预期事实、纯向量 Top3、混合检索 Top3 与命中结果。 |
| [秒杀压测](docs/project-proof/seckill-pressure-test.md) | 10,000 名不同用户、30 秒 Ramp-Up、100 张券的原始聚合指标和 MySQL/Redis 对账结果。 |
| [秒杀对账 SQL](docs/project-proof/seckill-verify.sql) | 订单数、唯一用户数、MySQL 库存的核对语句，以及对应 Redis 核对命令。 |
| [知识库故障恢复](docs/project-proof/knowledge-sync-failure.md) | Qdrant 不可用时同步任务持久化重试，恢复后最终同步成功。 |
| [AI 调用 Trace](docs/project-proof/ai-agent-trace.md) | 两条真实问题的召回、工具、SSE、请求耗时和 Token 记录。 |
| [缓存与秒杀可靠性](docs/project-proof/reliability-verification.md) | MQ 发送失败回滚、DLQ 补偿、3 条真实 Spring 集成测试。 |
| [支付与超时竞争](docs/project-proof/payment-timeout-race.md) | 支付先到 / 关单先到等订单状态验证。 |

集成测试执行：

```bash
mvn -q -Dtest=ReliabilityIntegrationTest test
```

最近一次真实环境结果：`Tests run: 3, Failures: 0, Errors: 0`。

<a id="trace"></a>

## Trace：运行链路记录

| 场景 | 覆盖情况 | 记录 |
| --- | --- | --- |
| 成功 Trace：优惠券与探店笔记查询 | 已留存真实请求、RAG 候选、3 次工具调用、SSE、耗时与 Token | [`ai-agent-trace.md#trace-1优惠券与探店笔记查询`](docs/project-proof/ai-agent-trace.md#trace-1优惠券与探店笔记查询) |
| 成功 Trace：朋友聚餐推荐 | 已留存真实召回、快速工具路由、最终回答及请求日志 | [`ai-agent-trace.md#trace-2朋友聚餐推荐`](docs/project-proof/ai-agent-trace.md#trace-2朋友聚餐推荐) |
| 工具失败分支 | 代码会写入 `success=0` 的工具日志、丢弃失败工具结果，并继续生成最终 SSE；当前未把一次真实故障输出固化为独立文档 | [`AiReadOnlyToolServiceImpl.invoke`](src/main/java/com/hmdp/service/impl/AiReadOnlyToolServiceImpl.java) |
| 未登录访问 AI 接口 | `LoginInterceptor` 在 `UserHolder` 无用户时返回 HTTP `401`，保护会话与 AI 接口 | [`LoginInterceptor`](src/main/java/com/hmdp/utils/LoginInterceptor.java) |
| 中断恢复 Trace | 已留存 Qdrant 中断后的任务重试恢复，以及 MQ 发送失败 / DLQ 最终补偿 | [知识库恢复](docs/project-proof/knowledge-sync-failure.md)、[秒杀恢复](docs/project-proof/reliability-verification.md) |

> AI 工具仅开放店铺、附近店铺、优惠券和公开探店笔记等只读能力；写操作仍由原有业务接口和登录鉴权控制。

<a id="failure-recovery"></a>

## Failure Recovery：故障与恢复

| 故障点 | 检测与恢复机制 | 幂等 / 一致性边界 | 证据 |
| --- | --- | --- | --- |
| 缓存重建锁过期后被其他线程重新获取 | 唯一持有者标识 + Lua 比较删除 | 旧持有者无法删除新锁 | [集成测试](docs/project-proof/reliability-verification.md#automated-regression-tests) |
| RocketMQ 订单消息发送失败 | 同步发送异常后执行 `seckill_rollback.lua` | 订单级预占标记只允许归还一次 Redis 库存 | [发送失败回滚](docs/project-proof/reliability-verification.md#producer-send-failure-restores-the-redis-reservation) |
| 订单消费者重试耗尽 | 消息进入 DLQ，由补偿消费者释放 Redis 预占 | 重复 DLQ 消息不会重复增加库存 | [DLQ 补偿](docs/project-proof/reliability-verification.md#consumer-final-failure-and-dlq-compensation) |
| 超时任务消息投递失败 | 任务表记录错误并指数退避重试 | CAS 抢占避免多个投递器并发处理同一版本 | [支付与关单验证](docs/project-proof/payment-timeout-race.md) |
| 支付回调与超时关单并发 | 共用订单级 Redisson 锁并按当前状态条件更新 | 已支付订单不能被关单；已关单订单不能再次归还库存 | [竞争验证](docs/project-proof/payment-timeout-race.md) |
| Qdrant 暂时不可用 | MySQL 同步任务记录失败、指数退避，并恢复超时任务 | 对已落库任务以版本号 CAS 抢占，恢复后更新最新店铺文档 | [知识库故障 Trace](docs/project-proof/knowledge-sync-failure.md) |
| 只读业务工具异常 | 捕获异常、记录失败工具日志并隔离失败结果 | 单个工具失败不直接中断 SSE 主流程 | [`AiReadOnlyToolServiceImpl`](src/main/java/com/hmdp/service/impl/AiReadOnlyToolServiceImpl.java) |

## 常用 Redis Key

| Key | 类型 | 用途 |
| --- | --- | --- |
| `login:token:{token}` | Hash | 登录用户信息 |
| `cache:shop:{shopId}` | String | 店铺详情缓存 |
| `bf:shop:id` | BloomFilter | 合法店铺 ID 预过滤 |
| `shop:geo:{typeId}` | GEO | 店铺地理坐标 |
| `feed:{userId}` | ZSet | 关注 Feed 收件箱 |
| `sign:{userId}:{yyyyMM}` | Bitmap | 用户签到 |
| `seckill:stock:{voucherId}` | String | 秒杀 Redis 库存 |
| `seckill:order:{voucherId}` | Set | 已购买用户标记 |
| `seckill:reservation:{orderId}` | String | Redis 预扣订单标记 |
| `lock:order:{orderId}` | String | 支付与关单互斥锁 |

## 注意事项

- `application-local.yaml`、`tokens.csv`、IDE 环境变量都可能包含敏感信息，不要提交到 GitHub。
- 支付功能仅为模拟回调，不接入真实第三方支付。
- README 中的 RAG 指标来自固定 50 条、当前数据快照下的真实评测，不代表生产环境的通用准确率。
- RocketMQ、Redis、MySQL、Qdrant 任一不可用都会影响对应功能；本项目对订单与知识库同步已提供重试和补偿，但部署时仍应配置监控与告警。

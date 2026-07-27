# Niche Review

一个面向本地生活场景的 Java 后端项目，基于黑马点评业务扩展了 Redis 缓存治理、高并发秒杀、RocketMQ 可靠异步订单，以及 Qdrant RAG 智能导购。

项目重点不是“接入一个聊天接口”，而是将本地生活数据、检索、只读业务工具、会话记忆、SSE 流式输出和可观测日志串成可追溯的 AI 导购链路。

## 功能概览

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

## 架构概览

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

## 快速启动

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

## 秒杀可靠性链路

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

超时任务投递器
  -> CAS 抢占 PENDING 任务
  -> 发送 RocketMQ 延迟消息
  -> 发送失败指数退避重试

支付回调 / 延迟关单
  -> 同一把 Redisson order lock
  -> 订单状态 CAS
  -> 确保库存只回滚一次、已支付订单不被关单
```

## RAG 与 AI 对话链路

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

## 已验证材料

| 文档 | 内容 |
| --- | --- |
| [RAG 评测](docs/project-proof/rag-evaluation.md) | 50 条真实案例；43 条可命中题中混合检索 Hit@1 为 97.67%，Hit@3 为 100.00%；7 条无结果题均空召回且正确拒答。 |
| [RAG 案例集](docs/project-proof/rag-cases.csv) | 每题的预期事实、纯向量 Top3、混合检索 Top3 与命中结果。 |
| [知识库故障恢复](docs/project-proof/knowledge-sync-failure.md) | Qdrant 不可用时同步任务持久化重试，恢复后最终同步成功。 |
| [AI 调用 Trace](docs/project-proof/ai-agent-trace.md) | 两条真实问题的召回、工具、SSE、请求耗时和 Token 证据。 |
| [缓存与秒杀可靠性](docs/project-proof/reliability-verification.md) | MQ 发送失败回滚、DLQ 补偿、3 条真实 Spring 集成测试。 |
| [支付与超时竞争](docs/project-proof/payment-timeout-race.md) | 支付先到 / 关单先到等订单状态验证。 |

集成测试执行：

```bash
mvn -q -Dtest=ReliabilityIntegrationTest test
```

最近一次真实环境结果：`Tests run: 3, Failures: 0, Errors: 0`。

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

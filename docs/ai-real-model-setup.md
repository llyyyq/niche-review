# Phase 1: 真实模型接入与验收

## 1. 当前代码支持的模型协议

项目已实现 `openai-compatible` 提供方。它直接请求一个完整的 OpenAI Chat Completions 地址，并接收 `data: {...}` / `data: [DONE]` 格式的流式响应。

默认仍是 `mock`，因此没有 API Key 时项目可以正常启动和演示 SSE 链路。

## 2. 推荐本地配置：DeepSeek

不要把 API Key 放到 `application.yaml`、Git 或截图中。可以在 IDEA 的运行配置中增加下列环境变量：

```text
AI_CHAT_PROVIDER=openai-compatible
AI_CHAT_BASE_URL=https://api.deepseek.com/chat/completions
AI_CHAT_MODEL=deepseek-v4-flash
AI_CHAT_API_KEY=你的 DeepSeek API Key
```

然后仍用现有的启动参数：

```text
--spring.profiles.active=local
```

Spring Boot 会把 `AI_CHAT_PROVIDER` 这类环境变量按宽松规则绑定为 `ai.chat.provider`，所以不需要把 Key 写进任何配置文件。

也可以在 `application-local.yaml` 中配置同名的 `ai.chat` 属性；该文件已被 `.gitignore` 忽略。无论使用哪一种方式，都不能提交真实 Key。

## 3. 为什么使用 `deepseek-v4-flash`

DeepSeek 当前提供 OpenAI 兼容的 `/chat/completions` 流式接口；`deepseek-v4-flash` 适合先完成本项目的本地生活问答验证。模型名称和接口说明以 DeepSeek 官方文档为准：<https://api-docs.deepseek.com/>。

本项目自行通过 `HttpURLConnection` 发起 HTTP 请求，因此 `AI_CHAT_BASE_URL` 必须填写完整接口地址，而不是只填域名。

## 4. Phase 1 验收步骤

1. 以带有上述环境变量的运行配置启动后端，确认日志中没有 Bean 创建失败。
2. 登录小众点评，打开 `http://localhost:8080/ai-chat.html`。
3. 新建会话，先问：`我和朋友周末想吃火锅，人均 150 元。`
4. 等助手完整回复后继续问：`离西湖近一点。`
5. 第二轮回答应能理解“火锅”和“人均 150 元”来自上一轮，而不是要求用户重复条件。
6. 刷新页面，确认两轮用户消息和两轮助手回复仍保存在同一会话中。

## 5. SSE 事件

成功请求的事件顺序为：

```text
message_start  { conversationId, messageId }
delta          { content }
delta          { content }
...
message_end    { messageId, finishReason: "stop" }
```

模型调用失败时，助手消息会以失败状态保存，浏览器收到：

```text
error { code: "AI_GENERATION_FAILED", message: "AI回复生成失败" }
```

失败不会把已生成的一小段文本标记为正常完成。

## 6. Phase 2 的 Embedding 与 Qdrant 配置

店铺知识库使用阿里云百炼 `text-embedding-v4` 生成 1024 维向量，Qdrant 负责存储与检索。IDEA 运行配置中新增：

```text
AI_EMBEDDING_PROVIDER=openai-compatible
AI_EMBEDDING_BASE_URL=https://你的WorkspaceId.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings
AI_EMBEDDING_MODEL=text-embedding-v4
AI_EMBEDDING_DIMENSION=1024
AI_EMBEDDING_API_KEY=你的百炼APIKey

AI_QDRANT_URL=http://your-qdrant-host:6333
AI_QDRANT_API_KEY=你的Qdrant API Key
```

首次写入店铺知识库时，临时增加：

```text
AI_KNOWLEDGE_REBUILD_ON_START=true
```

应用启动时会读取 `tb_shop` 与 `tb_shop_type`，构建店铺描述，按每批 10 条调用 Embedding 服务，然后完整重建 Qdrant 的 `shop_knowledge` collection。看到 `AI shop knowledge index rebuilt on startup` 日志后，必须把该变量改回 `false` 或删除，再次启动；否则每次重启都会删除并重建知识库。

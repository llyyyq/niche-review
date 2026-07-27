# Phase 7: Hybrid Retrieval and Bounded Think-Execute Agent

## Hybrid Retrieval

`ShopKnowledgeServiceImpl.searchRelevantShops()` now uses this order:

1. Query Qdrant using the question embedding.
2. When the vector result is empty or the best score is below `ai.knowledge.vector-min-score`, run a keyword fallback over store name, category, business area, and address.
3. Merge keyword matches with vector matches by store ID and keep the configured retrieval limit.

The keyword fallback is intentionally local and deterministic. It lets exact queries such as a store name, `KTV`, or a business area still work during weak vector recall or a temporary embedding/Qdrant failure.

## Think-Execute Loop

`AiAgentRunner` introduces a bounded agent loop:

1. The model receives the user question and currently available tool names.
2. It returns strict JSON indicating either `tool` with tool names, or `final`.
3. The application checks the tool allowlist and executes requested tools.
4. The model can plan another step with the live tool results available in context.
5. The loop stops on `final`, no executable tool, a planner failure, or `ai.agent.max-steps`.

Planner failures safely fall back to the previous keyword tool routing. Tool failures are isolated and logged; they do not fail the entire chat response.

## Fast Path for Chat Latency

Calling a model just to decide whether to invoke an obvious read-only tool adds several seconds of latency. With `ai.agent.fast-path-enabled: true`, the application now uses deterministic routing before the planner:

1. Questions mentioning vouchers, public blogs, an explicit shop number, shop details, or nearby stores with browser coordinates call the existing allowlisted tools directly.
2. Normal chat and knowledge-only questions skip tool planning and go straight to the final answer model.
3. Only ambiguous recommendation, comparison, budget, dating, or group-dining questions use the model planner.

The default `max-steps` is `1`, so a complex question can add at most one planning model round trip. Set `fast-path-enabled: false` only when you need to compare the complete planner behavior.

## External Tool Extension

`AiExternalToolProvider` and `AiToolInvocation` are extension points. A future MCP Client adapter can implement `AiExternalToolProvider`, publish discovered tool names, and execute MCP calls without changing `AiAgentRunner`.

This project does not add Spring AI or an MCP dependency yet. The current application uses Spring Boot 2.3 and Java 8, while current Spring AI requires a modern Spring Boot/JDK baseline. The extension seam keeps the current project stable while preserving a clean migration path.

## Configuration

```yaml
ai:
  knowledge:
    keyword-fallback-enabled: true
    vector-min-score: 0.35
    keyword-fallback-limit: 3
  agent:
    enabled: true
    max-steps: 1
    max-tools-per-step: 3
    fast-path-enabled: true
```

## Verification

1. Restart the backend with the existing chat, embedding, and Qdrant configuration.
2. Ask a store-specific question such as `店铺 10 有什么优惠券和探店笔记？`.
3. Check `tb_ai_tool_log` for `shopDetail`, `voucherQuery`, and/or `blogSearch` records.
4. To force a keyword-fallback test temporarily, set `AI_KNOWLEDGE_VECTOR_MIN_SCORE=1`, restart, and ask an exact store-name or category question. The log should contain `Keyword fallback supplemented vector retrieval`.
5. Remove the temporary score override after the test.

# Phase 7 Historical Retrieval Design

> This document records the retired first-generation RAG design. It is not the current production workflow.

The historical version embedded the user query directly, searched Qdrant, and used a local keyword fallback when vector recall was empty or below a score threshold. It also used a bounded model-based tool-planning loop with deterministic fast paths.

This design was replaced because the keyword rules reparsed raw natural language independently from the model, mixed hard business conditions with semantic ranking, and made multi-task condition ownership difficult to verify.

The current workflow is documented in [README.md](../README.md#结构化-rag-检索):

1. The model produces a strict task-scoped query plan for every non-empty request.
2. Java validates the plan and resolves names into database identifiers.
3. MySQL applies explicit store, exclusion, category, area, and budget conditions.
4. Embedding and Qdrant run only when a task contains subjective semantic preferences.
5. Tools are selected from the validated `requiredFacts` field and executed per task.

Historical evaluation outputs remain under `docs/project-proof/archive/` for regression context. They must not be presented as current production metrics.

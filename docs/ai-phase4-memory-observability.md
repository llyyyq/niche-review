# AI Phase 4: Conversation Memory and Observability

## Context policy

Every chat request builds its prompt in this order:

```text
system instructions
-> response-style constraints
-> optional browser-location availability notice
-> rolling conversation summary
-> bounded recent completed messages
-> bounded Qdrant knowledge context
-> bounded live read-only tool results
-> current user question
```

The current question is always appended last. This keeps the immediate request prominent while preserving the useful parts of a long conversation.

## Rolling summary memory

- The recent-message window remains `ai.chat.context-message-limit` (default: 6 completed messages).
- When unsummarized completed messages exceed `ai.memory.summary-trigger-message-count` (default: 12), the older messages are merged with the existing conversation summary by the configured chat model.
- The summary is stored in `tb_ai_conversation.summary`; `summary_up_to_message_id` records the last message that it covers.
- The last six messages remain raw, so short-term dialogue context is not lost.
- Summary execution runs asynchronously after the SSE answer has completed. It does not delay the current response.

## Prompt budgets and token estimates

The following configuration protects the prompt from unlimited growth:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `ai.memory.max-summary-chars` | 2400 | maximum stored summary added to a chat prompt |
| `ai.memory.max-recent-message-chars` | 6000 | maximum combined raw-history characters |
| `ai.memory.max-knowledge-chars` | 6000 | maximum Qdrant context characters |
| `ai.memory.max-tool-result-chars` | 4000 | maximum live-tool result characters |

`input_tokens` and `output_tokens` are provider-independent estimates, not provider billing values. The estimator is deliberately lightweight so it works for both the mock client and OpenAI-compatible streaming clients. If a provider later returns exact `usage`, it can replace this estimator without changing tables or APIs.

## Request observability

Run `src/main/resources/db/upgrade_20260718_ai_memory_observability.sql` for an existing database. It creates `tb_ai_request_log`.

Each `chat` and `summary` model request records provider, model, retrieval time, tool time, first-token time, total model time, estimated input/output tokens, success state, and a sanitized error message.

Example query:

```sql
SELECT request_type, provider, model, retrieval_ms, tool_ms, first_token_ms,
       total_ms, input_tokens, output_tokens, success, create_time
FROM tb_ai_request_log
ORDER BY id DESC;
```

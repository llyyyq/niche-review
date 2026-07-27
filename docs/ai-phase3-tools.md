# AI Phase 3: Read-only Business Tools

## Goal

Keep the LLM responsible for understanding and explaining, while current business facts are read from the existing application services. The AI assistant never receives an order, payment, stock deduction, or other write-capable tool.

## Orchestration

```text
question
  -> Qdrant retrieves relevant store candidates
  -> shopDetail always validates the candidates against current data
  -> voucherQuery runs for voucher/discount intent
  -> blogSearch runs for blog/review intent
  -> nearbyShopSearch runs for nearby intent when longitude and latitude are supplied
  -> RAG context plus live tool results form the model prompt
  -> model response is streamed with SSE
```

## Implemented tools

| Tool | Data source | Trigger | Read-only result |
| --- | --- | --- | --- |
| `shopDetail` | `tb_shop` | Every question with RAG candidates | Current rating, average spend, address, opening hours |
| `voucherQuery` | `tb_voucher` + `tb_seckill_voucher` | Coupon, discount, voucher, seckill intent | Current enabled vouchers; excludes not-started, expired, and sold-out seckill vouchers |
| `blogSearch` | `tb_blog` | Blog, review, note, reputation intent | Current popular public-blog summaries |
| `nearbyShopSearch` | Redis GEO + `tb_shop` | Nearby/distance intent and request `x`, `y` are provided | Current nearby stores and calculated distance |

## Observability

Each invocation is stored in `tb_ai_tool_log`, including the conversation, user, tool name, sanitized request/result summary, success flag, and duration. Tool failures are logged and excluded from the prompt, so a temporary business-query failure does not prevent the chat response.

## Database upgrade

For an existing database, execute:

```sql
source src/main/resources/db/upgrade_20260717_ai_tool_log.sql;
```

For a new database, `src/main/resources/db/hmdp.sql` already includes `tb_ai_tool_log`.

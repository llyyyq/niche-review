# Phase 5: Incremental Knowledge Synchronization

## Objective

Keep a store's Qdrant document synchronized after local-life business data changes, without rebuilding every store or restarting the application.

## Triggered Business Writes

- Create or update a store: `ShopServiceImpl`
- Create a normal or seckill voucher: `VoucherServiceImpl`
- Publish a shop blog: `BlogServiceImpl`

Each successful write publishes `ShopKnowledgeChangedEvent(shopId)`.

## Execution Flow

1. A business service writes MySQL successfully.
2. It publishes the store-change event.
3. For transactional methods, `@TransactionalEventListener(AFTER_COMMIT)` waits for the database transaction to commit.
4. `aiKnowledgeExecutor` runs the synchronization outside the request thread.
5. The synchronizer reloads that store, its currently enabled vouchers, and its public blogs.
6. It generates a fresh embedding and performs an upsert to Qdrant using the store ID as the point ID.

An upsert replaces the old vector and payload for the same store. It does not create duplicate points.

## Failure Behavior

The original store, voucher, or blog write remains successful if embedding or Qdrant fails. The listener logs the failure and a later full rebuild can repair the vector index. This keeps a non-critical AI enhancement from breaking core business writes.

The listener is enabled only when `AI_EMBEDDING_PROVIDER=openai-compatible`. A local/default profile with embeddings disabled will not attempt remote vector calls.

## Verification

With the embedding and Qdrant environment variables configured:

1. Start the backend.
2. Update a store, create a voucher, or publish a shop blog.
3. Check the backend log for `Shop knowledge point synchronized, shopId=...`.
4. Ask the AI a related question. The RAG context should include the updated store data.

Voucher time windows can change without a database write. The AI's live `voucherQuery` tool is still authoritative for real-time voucher availability; a scheduled full rebuild can be added later if vector text must also reflect time-based expiration immediately.

# Phase 6: Durable Knowledge Synchronization Retry

## Why a Database Task Is Needed

The earlier incremental synchronization ran directly in an asynchronous event listener. If Qdrant, the embedding provider, or the application process failed at that moment, the update was only recorded in logs and could be lost.

This phase persists every required store synchronization in MySQL before attempting the remote call.

## Data Model

`tb_ai_knowledge_sync_task` has one row per store. The unique `shop_id` key coalesces many quick updates for the same store into one latest synchronization task.

Statuses:

- `0` pending: eligible for processing.
- `1` processing: claimed by one worker.
- `2` succeeded: Qdrant upsert completed.
- `3` failed: all configured attempts failed and the task is retained for operational inspection.

The `version` column prevents an older worker from marking a task as succeeded after a newer business update has already requeued it.

## Processing Flow

1. A store, voucher, or blog write publishes `ShopKnowledgeChangedEvent` after commit.
2. The listener performs an `INSERT ... ON DUPLICATE KEY UPDATE` to mark that store's task pending.
3. A background worker atomically claims the task.
4. It calls `syncShopKnowledge(shopId)` to rebuild and upsert the store's Qdrant point.
5. On failure it records `last_error` and schedules exponential retry delays: 10s, 20s, 40s, 80s by default.
6. After five failed attempts the task becomes status `3` instead of being silently dropped.
7. Every 10 seconds, the scheduler scans pending tasks. It also returns stuck processing tasks to pending after 120 seconds, covering a process crash or timeout.

## Configuration

```yaml
ai:
  knowledge:
    incremental-sync-enabled: true
    sync-retry-max-attempts: 5
    sync-retry-base-delay-seconds: 10
    sync-retry-scan-delay-ms: 10000
    sync-processing-timeout-seconds: 120
```

The mechanism is active only when `AI_EMBEDDING_PROVIDER=openai-compatible`.

## Required Migration

Run `src/main/resources/db/upgrade_20260718_ai_knowledge_sync_task.sql` once in the existing `hmdp` database before starting the backend.

## Verification

1. Publish a blog for a store while Qdrant and the embedding service are healthy.
2. Check `tb_ai_knowledge_sync_task`: the store task should have `status=2` and `retry_count=1`.
3. To test retries, temporarily make the Qdrant URL unavailable, then publish another blog for that store.
4. The task should remain at `status=0`, with `retry_count` increasing and `next_retry_at` moving forward.
5. Restore Qdrant before the final attempt. The next scan should update the task to `status=2`.

For a task at `status=3`, keep it for diagnosis. A future administrative operation can explicitly requeue it after the external fault is fixed.

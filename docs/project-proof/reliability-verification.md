# Cache And Seckill Reliability Verification

## Build Result

`mvn -q -DskipTests compile` completed successfully on 2026-07-23.

## Automated Regression Tests

The three previously manual cache/idempotency boundaries were converted into
repeatable Spring integration tests on `2026-07-27`:

```text
mvn -q -Dtest=ReliabilityIntegrationTest test
```

Result: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` in `14.722 s`.

| Test | Verification |
| --- | --- |
| `shouldEvictShopCacheOnlyAfterSuccessfulCommit` | A rollback keeps both the previous database value and the previous Redis cache value. During a successful transaction the cache still exists; after commit it is deleted. |
| `shouldNotDeleteLockReacquiredByAnotherThread` | Owner A lets its one-second lock expire; owner B acquires the same Redis lock. A's later unlock does not remove B's lock, and B can release it normally. |
| `shouldCreateOnlyOneOrderWhenSameMessageIsConsumedTwice` | The same serialized order message is passed to `VoucherOrderConsumer` twice. MySQL retains one order and one timeout task, and stock is deducted once only. |

The duplicate-message test created a temporary voucher, order, timeout task,
and Redis stock key, then removed all of them in `@AfterEach`; it does not rely
on or modify existing voucher records.

## Actual Verification Evidence

### Producer send failure restores the Redis reservation

Verified on `2026-07-26` with the currently running Redis, MySQL, backend and RocketMQ environment.

| Checkpoint | Actual result |
| --- | --- |
| Test voucher | `voucherId=13`; MySQL stock was `100`. |
| Baseline | The test user had no `tb_voucher_order` row for voucher 13; Redis `seckill:stock:13` was `100`; `SISMEMBER seckill:order:13 <userId>` returned `0`. |
| Failure injection | Only the RocketMQ Broker was stopped; backend, Redis, MySQL and NameServer remained available. |
| Request | `POST /voucher-order/seckill/13` with the logged-in user's Authorization token. |
| HTTP result | `{"success":false,"errorMsg":"系统繁忙，请稍后重试"}`. |
| Final Redis state | `GET seckill:stock:13` remained `100`; `SISMEMBER seckill:order:13 <userId>` remained `0`. |
| Final MySQL state | No order row was created for the test user and voucher 13. |

Conclusion: after the Lua script temporarily reserves stock and the one-user marker, a synchronous RocketMQ producer-send failure invokes `seckill_rollback.lua`. The script deletes the reservation marker first, then restores the user marker and Redis stock exactly once; no MySQL order is created.

### Consumer final failure and DLQ compensation

Verified on `2026-07-26` with an intentionally inconsistent test voucher to force
the order consumer to fail after the Redis reservation had succeeded.

| Checkpoint | Actual result |
| --- | --- |
| Test voucher | `voucherId=15`; MySQL stock was deliberately set to `0`, while Redis `seckill:stock:15` was set to `1`. |
| Accepted request | User `2013` submitted one request and received order ID `618859870585618434`. Redis then held the stock reservation, user-order marker, and order-level reservation marker. |
| Consumer failure | The order consumer failed to decrement MySQL stock and retried. RocketMQ recorded the original message in `%DLQ%hmdp-voucher-order-consumer-group` with `reconsumeTimes=6`. |
| DLQ message body | `{"userId":2013,"voucherId":15,"id":618859870585618434}`. |
| Defect found | The previous DLQ listener acknowledged a compensation result even when Redis reservation state had not been verified. The message was consumed but Redis remained at stock `0`, with user `2013` and the reservation marker still present. |
| Fix | `compensateSeckillReservation()` now returns success only after Lua releases the reservation, or after both reservation and user-order markers are confirmed absent. The DLQ listener throws on any unverified result so RocketMQ retries instead of acknowledging it silently. |
| Retest | After restarting the backend, the same DLQ message was republished once at `18:59:26`. The DLQ compensation consumer acknowledged it without entering its retry topic. |
| Final Redis state | `GET seckill:stock:15` returned `"1"`; `SMEMBERS seckill:order:15` returned an empty array; `EXISTS seckill:reservation:618859870585618434` returned `0`. |
| Final MySQL state | No `tb_voucher_order` row existed for order ID `618859870585618434`. MySQL voucher stock remained `0`, because no database deduction had succeeded. |

Conclusion: a message that exhausts normal order-consumption retries is routed to
the DLQ. Its compensation is idempotent through the order-level reservation key:
the first successful rollback restores Redis stock and removes the user marker;
duplicate DLQ deliveries observe the already-released state and do not add stock
again. The listener now treats an unverified rollback as a failure, preventing a
silent loss of the compensation action.

### Payment callback and timeout-close ordering

The two ordered integration cases, including the task-table constraint defect
found during testing and its repair, are recorded in
[`payment-timeout-race.md`](payment-timeout-race.md).

## Required Database Upgrade

Before starting the application against an existing database, execute:

```sql
SOURCE src/main/resources/db/upgrade_20260723_order_timeout_task.sql;
```

The script creates the durable timeout-delivery task table only. It does not
create tasks for existing unpaid orders.

## Manual Integration Checks

1. Stop RocketMQ, submit a seckill order, and verify the producer returns a
   failure response and the reservation marker, Redis stock, and user-order
   marker are rolled back once.
2. Start RocketMQ, submit an order, and verify that one `tb_voucher_order` row
   and one pending `tb_order_timeout_task` row are created by the same consumer
   transaction. Within five seconds the timeout task should become `SENT`.
3. Force the order consumer to fail repeatedly. After five retries, inspect
   `%DLQ%hmdp-voucher-order-consumer-group`; verify that its compensation
   listener restores the Redis reservation only once.
4. Send the same order message twice. The second delivery must be treated as
   `DUPLICATE_ORDER_ID` and must not change either MySQL or Redis stock.
5. Simulate a payment callback before the timeout deadline. Verify that the
   order becomes paid. A `PENDING` timeout task is cancelled; a task already
   marked `SENT` may remain for delivery, but its timeout message must be
   ignored because the order is no longer unpaid.
6. Simulate duplicate timeout messages. Verify that only the first can change
   an unpaid order to cancelled and restore MySQL stock.

## Query Helpers

```sql
SELECT id, order_id, due_at, status, retry_count, next_retry_at, processing_at, last_error, version
FROM tb_order_timeout_task
ORDER BY id DESC;

SELECT id, user_id, voucher_id, status, create_time, pay_time
FROM tb_voucher_order
ORDER BY create_time DESC;
```

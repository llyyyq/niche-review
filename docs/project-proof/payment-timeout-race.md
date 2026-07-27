# Payment Callback And Timeout Close Verification

## Scope

This document records two real integration checks for the same order state
machine:

- `1`: unpaid
- `2`: paid
- `4`: cancelled because payment timed out

Both the payment callback and the timeout consumer obtain the same Redisson
lock, `lock:order:{orderId}`. The database update also includes the expected
current status in its `WHERE` clause, so only one transition can win.

## Defect Found During Verification

`tb_order_timeout_task.next_retry_at` is declared `NOT NULL`, but the original
mapper set it to `NULL` when a task became `SENT` or `CANCELLED`. The task could
send a timeout message successfully, then fail while updating its own state and
be retried repeatedly.

The mapper was corrected on `2026-07-26` to retain `due_at` as the non-null
historical value for terminal task states. The scheduler only scans
`status = PENDING`, therefore terminal tasks are not redelivered because of
that value. `mvn -q -DskipTests compile` passed after the change.

## Case 1: Timeout Close Before Payment Callback

| Item | Actual value |
| --- | --- |
| Test voucher | `voucherId=16`, initial MySQL and Redis stock `1` |
| Order | `orderId=618922504093696003`, `userId=2014` |
| Initial order state | `status=1` (unpaid), created at `2026-07-26 20:55:26` |
| Final timeout task state | `status=3` (cancelled), `retry_count=9` |
| Final order state | `status=4` (cancelled) |
| Final MySQL stock | `1` |
| Late callback | `POST /payment/callback/wechat/618922504093696003` returned `FAIL` |

The first eight task attempts exposed the `next_retry_at = NULL` defect. After
the mapper repair and backend restart, the next task execution reached the
terminal state. The timeout consumer changed the unpaid order to cancelled and
restored stock once. The subsequent payment callback was rejected and could not
reverse the state.

## Case 2: Payment Callback Before Timeout Message

| Item | Actual value |
| --- | --- |
| Test voucher | `voucherId=18`, initial MySQL and Redis stock `1` |
| Order | `orderId=619172265031892993`, `userId=2015` |
| Initial order state | `status=1` (unpaid), created at `2026-07-27 13:04:38` |
| Timeout task | `status=2` (sent), `retry_count=1`, due at `2026-07-27 13:19:38` |
| Payment | `POST /payment/simulate/619172265031892993` returned success; `pay_time=2026-07-27 13:07:02` |
| Timeout injection | An immediate duplicate message for this order was sent to `hmdp-order-timeout-topic` at `2026-07-27 13:07:35` and consumed with no retry. |
| Final order state | `status=2` (paid) |
| Final MySQL stock | `0` |
| Final Redis stock | `GET seckill:stock:18` returned `"0"` |
| Actual delayed-message check | After the original deadline at `2026-07-27 13:19:38`, the order was still `status=2` with `pay_time=2026-07-27 13:07:02`; MySQL stock was still `0`. |

The immediate message uses the same `OrderTimeoutListener` path as the delayed
message. It acquired the order lock, observed that the order was no longer
unpaid, and exited without closing the order or returning stock.
The later check after the real 15-minute delayed message confirmed the same
result, so the test does not rely only on manual message injection.

## Conclusion And Boundary

The two tests prove both ordering outcomes:

1. timeout first: the late callback cannot change a cancelled order back to paid;
2. payment first: a late or duplicate timeout message cannot cancel a paid order
   or return MySQL/Redis stock.

These are ordered integration tests, not a load test that forces two threads to
reach the lock at the exact same CPU instant. The protection for a true
simultaneous race is the shared Redisson order lock plus the conditional MySQL
status update; only one state transition can succeed.

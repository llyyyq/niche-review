-- 秒杀压测最终一致性核对。
-- 将 23 替换为本轮压测创建的 voucher_id。

-- 1. 订单数量与唯一用户数量。两者都应等于初始库存 100。
SELECT
    COUNT(*) AS order_count,
    COUNT(DISTINCT user_id) AS distinct_user_count
FROM tb_voucher_order
WHERE voucher_id = 23;

-- 2. MySQL 秒杀库存。应为 0。
SELECT voucher_id, stock
FROM tb_seckill_voucher
WHERE voucher_id = 23;

-- 3. 可选：确认每个用户最多一单。结果应为空。
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
WHERE voucher_id = 23
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

-- Redis CLI 核对命令：
-- GET seckill:stock:23              -- 预期 "0"
-- SCARD seckill:order:23            -- 预期 100

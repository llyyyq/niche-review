-- 1. Parameters
-- 1.1 voucher id
local voucherId = ARGV[1]
-- 1.2 user id
local userId = ARGV[2]
-- 1.3 generated order id
local orderId = ARGV[3]

-- 2. Redis keys
-- 2.1 stock key: seckill:stock:{voucherId}
local stockKey = "seckill:stock:" .. voucherId
-- 2.2 order key: seckill:order:{voucherId}, stores user ids that already ordered
local orderKey = "seckill:order:" .. voucherId
-- 2.3 reservation key: seckill:reservation:{orderId}
local reservationKey = "seckill:reservation:" .. orderId

-- 3. Business checks
-- 3.1 Check stock
local stock = redis.call("get", stockKey)
if (stock == false or tonumber(stock) <= 0) then
    return 1
end

-- 3.2 Check duplicate order
if (redis.call("sismember", orderKey, userId) == 1) then
    return 2
end

-- 3.3 Deduct Redis stock
redis.call("incrby", stockKey, -1)
-- 3.4 Mark user as ordered
redis.call("sadd", orderKey, userId)
-- 3.5 Keep an order-level marker for idempotent failure compensation.
redis.call("set", reservationKey, voucherId .. ":" .. userId, "EX", 86400)
return 0

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId
local reservationKey = "seckill:reservation:" .. orderId

-- A previous database order already exists. Return only this message's
-- Redis reservation, then restore the user's one-order marker for that
-- previous order.
if (redis.call("del", reservationKey) == 1) then
    redis.call("incrby", stockKey, 1)
    redis.call("sadd", orderKey, userId)
    return 1
end
return 0

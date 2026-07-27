local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId
local reservationKey = "seckill:reservation:" .. orderId

if (redis.call("del", reservationKey) == 1) then
    redis.call("srem", orderKey, userId)
    redis.call("incrby", stockKey, 1)
    return 1
end
return 0

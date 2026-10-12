local orderId = ARGV[1]
local voucherId = ARGV[2]

local stockKey = "seckill:stock:" .. voucherId
local releaseKey = "seckill:close:reservation:" .. orderId

-- Do not create a stock key with an unknown value after a Redis data loss.
if (redis.call("exists", stockKey) == 0) then
    return -1
end

-- A repeated post-commit attempt must not return stock twice.
if (redis.call("set", releaseKey, "1", "NX", "EX", 7776000) == false) then
    return 0
end

redis.call("incrby", stockKey, 1)
return 1

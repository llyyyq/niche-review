local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = "seckill:stock:" .. voucherId
-- The database status CAS guarantees only one close operation can reach this
-- script. Keep the user marker because the database unique index enforces one
-- voucher order per user for the whole activity, even after cancellation.
redis.call("incrby", stockKey, 1)
return 1

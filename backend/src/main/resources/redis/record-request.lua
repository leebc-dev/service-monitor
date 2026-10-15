local now = tonumber(ARGV[1])
local cutoff = now - 300000
local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', cutoff)
for _, id in ipairs(expired) do
    redis.call('HDEL', KEYS[2], id)
end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
local occurred = tonumber(ARGV[2])
if occurred <= cutoff or occurred > now + 60000 then return 0 end
if redis.call('ZADD', KEYS[1], 'NX', occurred, ARGV[3]) == 1 then
    redis.call('HSET', KEYS[2], ARGV[3], ARGV[4])
end
redis.call('EXPIRE', KEYS[1], 360)
redis.call('EXPIRE', KEYS[2], 360)
return 1

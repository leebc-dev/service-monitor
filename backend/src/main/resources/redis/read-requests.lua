local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '(' .. ARGV[1], ARGV[2])
local result = {}
for _, id in ipairs(ids) do
    local payload = redis.call('HGET', KEYS[2], id)
    if payload then table.insert(result, payload) end
end
return result

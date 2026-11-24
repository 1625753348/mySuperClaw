local key = KEYS[1]
local token = ARGV[3]
if redis.call('HGET', key, 'policyVersion') ~= ARGV[1] or redis.call('HGET', key, 'epoch') ~= ARGV[2] then
    return 0
end

local probe = redis.call('HGET', key, 'probe')
if probe and probe ~= token then return 0 end
local failed = ARGV[4] == '1'
if failed then
    local failures = redis.call('HINCRBY', key, 'failures', 1)
    if probe or failures >= tonumber(ARGV[5]) then
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        redis.call('HSET', key, 'openUntil', now + tonumber(ARGV[6]), 'epoch', 'open:' .. token)
    end
else
    redis.call('HSET', key, 'failures', 0, 'openUntil', 0)
    if probe then redis.call('HSET', key, 'epoch', 'closed:' .. token) end
end
redis.call('HDEL', key, 'probe', 'probeUntil')
redis.call('EXPIRE', key, 86400)
return 1

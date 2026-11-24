local key = KEYS[1]
local version = tonumber(ARGV[1])
local token = ARGV[2]
local current = tonumber(redis.call('HGET', key, 'policyVersion'))
if current and version < current then return nil end
if not current or version > current then
    redis.call('HSET', key, 'policyVersion', version, 'epoch', token, 'failures', 0, 'openUntil', 0)
    redis.call('HDEL', key, 'probe', 'probeUntil')
end
redis.call('EXPIRE', key, 86400)

local openUntil = tonumber(redis.call('HGET', key, 'openUntil'))
if openUntil > 0 then
    local time = redis.call('TIME')
    local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
    local probeUntil = tonumber(redis.call('HGET', key, 'probeUntil')) or 0
    if now < openUntil or now < probeUntil then return nil end
    redis.call('HSET', key, 'probe', token, 'probeUntil', now + tonumber(ARGV[3]))
end
return redis.call('HGET', key, 'epoch')

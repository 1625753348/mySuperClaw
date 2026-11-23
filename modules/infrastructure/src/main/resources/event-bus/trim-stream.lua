local stream = KEYS[1]
if redis.call('EXISTS', stream) == 0 then return 0 end

local groups = redis.call('XINFO', 'GROUPS', stream)
if #groups == 0 then return 0 end

local function earlier(left, right)
    local leftMs, leftSeq = string.match(left, '^(%d+)%-(%d+)$')
    local rightMs, rightSeq = string.match(right, '^(%d+)%-(%d+)$')
    -- 两段都是十进制整数。用位数和字符串比较，避免 Lua 的浮点精度截断。
    if leftMs ~= rightMs then
        if #leftMs ~= #rightMs then return #leftMs < #rightMs end
        return leftMs < rightMs
    end
    if #leftSeq ~= #rightSeq then return #leftSeq < #rightSeq end
    return leftSeq < rightSeq
end

local time = redis.call('TIME')
local cutoffMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) - tonumber(ARGV[1])
if cutoffMs <= 0 then return 0 end
local cutoff = string.format('%.0f-0', cutoffMs)

-- 在同一个脚本中读水位并裁剪，保护每个组的未读消息和 Pending。
for _, group in ipairs(groups) do
    local name, delivered
    for i = 1, #group, 2 do
        if group[i] == 'name' then name = group[i + 1] end
        if group[i] == 'last-delivered-id' then delivered = group[i + 1] end
    end
    local pending = redis.call('XPENDING', stream, name)
    if earlier(delivered, cutoff) then cutoff = delivered end
    if pending[1] > 0 and earlier(pending[2], cutoff) then cutoff = pending[2] end
end

-- 保证低流量的 Stream 也会清掉旧消息，同时限制单次脚本的删除量。
if cutoff == '0-0' then return 0 end
local eligible = redis.call('XRANGE', stream, '-', '(' .. cutoff, 'COUNT', 501)
if #eligible == 0 then return 0 end
if #eligible == 501 then cutoff = eligible[501][1] end
return redis.call('XTRIM', stream, 'MINID', cutoff)

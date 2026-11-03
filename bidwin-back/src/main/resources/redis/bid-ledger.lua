-- 단일 Redis 전용. 런타임 오류의 자동 롤백을 전제하지 않고 알려진 오류를 쓰기 전에 검사한다.
local function uint(v)
    return v and (v == '0' or string.match(v, '^[1-9]%d*$')) and tonumber(v) <= 9007199254740990
end
local function fail(reason)
    return reason
end
local op = ARGV[1]
local expected = {'hash', 'stream', 'hash', 'hash', 'hash', 'hash', 'hash'}
for i = 1, 7 do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expected[i] then return fail('ERROR_WRONGTYPE') end
end
local epoch = redis.call('HGET', KEYS[1], 'epoch')
local gen = redis.call('HGET', KEYS[1], 'gen')
local seq = redis.call('HGET', KEYS[1], 'seq')
local barriers = redis.call('HGET', KEYS[1], 'barriers')
if not uint(epoch) or not uint(gen) or not uint(seq) or not uint(barriers) then return fail('ERROR_META') end

if op == 'OPEN' or op == 'CALL' then
    if redis.call('EXISTS', KEYS[3]) == 1 then
        if redis.call('HGET', KEYS[3], 'fingerprint') ~= ARGV[3] then return 'BID_REQUEST_CONFLICT' end
        return redis.call('HGET', KEYS[3], 'status')
    end
end

if op == 'LOAD' then
    if tonumber(barriers) > 0 then return 'BUSY' end
    if epoch ~= ARGV[2] or gen ~= ARGV[11] then return 'RETRY' end
    local s = cjson.decode(ARGV[12])
    if s.memberStatus ~= 'ACTIVE' then return 'MEMBER_NOT_ACTIVE' end
    if s.auctionType ~= 'UP' then return 'NOT_UP_AUCTION' end
    if not uint(s.available) or not uint(s.locked) or tonumber(s.available) + tonumber(s.locked) > 9007199254740990
        or (s.amount ~= '-1' and not uint(s.amount)) or not uint(s.price) or not uint(s.version)
        or not uint(s.reserve) or tonumber(s.reserve) <= 0 or not uint(s.ended) then return 'ERROR_SNAPSHOT' end
    for i = 4, 6 do
        local dirty = redis.call('HGET', KEYS[7], KEYS[i]) or '0'
        if not uint(dirty) then return 'ERROR_PENDING' end
        if redis.call('EXISTS', KEYS[i]) == 0 and dirty ~= '0' then return 'ERROR_DIRTY_CACHE_MISSING' end
    end
    if redis.call('EXISTS', KEYS[4]) == 0 then
        redis.call('HSET', KEYS[4], 'available', s.available, 'locked', s.locked, 'pending', '0')
        redis.call('PEXPIRE', KEYS[4], ARGV[9])
    end
    if redis.call('EXISTS', KEYS[5]) == 0 then
        redis.call('HSET', KEYS[5], 'amount', s.amount, 'pending', '0')
        redis.call('PEXPIRE', KEYS[5], ARGV[9])
    end
    if redis.call('EXISTS', KEYS[6]) == 0 then
        redis.call('HSET', KEYS[6], 'price', s.price, 'version', s.version, 'seller', s.seller,
            'ended', s.ended, 'status', s.status, 'reserve', s.reserve, 'pending', '0')
        redis.call('PEXPIRE', KEYS[6], ARGV[9])
    end
    return 'READY'
end

if op == 'ACK' then
    if redis.call('HGET', KEYS[3], 'status') == 'DB_DONE' then
        redis.call('XDEL', KEYS[2], ARGV[13])
        return 'ACKED'
    end
    if ARGV[4] == 'OPEN' then
        if epoch ~= ARGV[2] then return 'ERROR_EPOCH' end
        for i = 4, 6 do
            local pending = redis.call('HGET', KEYS[i], 'pending')
            local dirty = redis.call('HGET', KEYS[7], KEYS[i])
            if not uint(pending) or not uint(dirty) or pending ~= dirty or pending == '0' then
                return 'ERROR_PENDING'
            end
        end
        for i = 4, 6 do
            local pending = redis.call('HINCRBY', KEYS[i], 'pending', -1)
            if pending == 0 then
                redis.call('HDEL', KEYS[7], KEYS[i])
                redis.call('PEXPIRE', KEYS[i], ARGV[9])
            else
                redis.call('HINCRBY', KEYS[7], KEYS[i], -1)
            end
        end
    else
        if barriers == '0' then return 'ERROR_BARRIER' end
        redis.call('HINCRBY', KEYS[1], 'barriers', -1)
        redis.call('HINCRBY', KEYS[1], 'epoch', 1)
    end
    redis.call('HINCRBY', KEYS[1], 'gen', 1)
    redis.call('HSET', KEYS[3], 'status', 'DB_DONE', 'result', ARGV[12])
    redis.call('XDEL', KEYS[2], ARGV[13])
    return 'ACKED'
end

local reserve = 0
local available = 0
local locked = 0
local version = 0
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if op == 'OPEN' then
    if tonumber(barriers) > 0 then return 'BUSY' end
    if epoch ~= ARGV[2] then return 'CACHE_MISS' end
    local function reject(reason)
        redis.call('HSET', KEYS[3], 'fingerprint', ARGV[3], 'status', reason)
        return reason
    end
    local price = tonumber(ARGV[8])
    if not price or price >= 100000000000 then return reject('PRICE_LIMIT_EXCEEDED') end
    if price <= 0 or price % 1000 ~= 0 then return reject('INVALID_BID_UNIT') end
    for i = 4, 6 do
        if redis.call('EXISTS', KEYS[i]) == 0 then return 'CACHE_MISS' end
        local pending = redis.call('HGET', KEYS[i], 'pending')
        local dirty = redis.call('HGET', KEYS[7], KEYS[i]) or '0'
        if not uint(pending) or not uint(dirty) or pending ~= dirty then return 'ERROR_PENDING' end
    end
    local current = redis.call('HGET', KEYS[6], 'price')
    local ended = redis.call('HGET', KEYS[6], 'ended')
    local revision = redis.call('HGET', KEYS[6], 'version')
    local held = redis.call('HGET', KEYS[5], 'amount')
    local balance = redis.call('HGET', KEYS[4], 'available')
    local reserved = redis.call('HGET', KEYS[4], 'locked')
    if not uint(current) or not uint(ended) or not uint(revision) or not uint(balance)
        or not uint(reserved) or (held ~= '-1' and not uint(held)) then return 'ERROR_STATE' end
    local status = redis.call('HGET', KEYS[6], 'status')
    if status ~= 'OPEN' and status ~= 'BID_ONGOING' then return reject('AUCTION_NOT_ONGOING') end
    if now >= tonumber(ended) then return reject('AUCTION_ALREADY_ENDED') end
    if now >= tonumber(ended) - 300000 then return reject('BID_PHASE_CHANGED') end
    if redis.call('HGET', KEYS[6], 'seller') == ARGV[6] then return reject('SELF_BID_NOT_ALLOWED') end
    if price < tonumber(current) + 1000 then return reject('BID_PRICE_TOO_LOW') end
    if held == '-1' then return reject('DEPOSIT_ALREADY_SETTLED') end
    available = tonumber(balance)
    locked = tonumber(reserved)
    if available + locked > 9007199254740990 or tonumber(held) > locked then return 'ERROR_BALANCE' end
    if held == '0' then
        local amount = redis.call('HGET', KEYS[6], 'reserve')
        if not uint(amount) or tonumber(amount) <= 0 then return 'ERROR_DEPOSIT' end
        reserve = tonumber(amount)
        if available < reserve then return reject('INSUFFICIENT_DEPOSIT') end
    end
    version = tonumber(revision) + 1
elseif op ~= 'CALL' then
    return 'ERROR_OPERATION'
end
if redis.call('XLEN', KEYS[2]) >= tonumber(ARGV[10]) then return 'BUSY' end
local nextSeq = string.format('%.0f', tonumber(seq) + 1)
redis.call('XADD', KEYS[2], '*', 'request', KEYS[3], 'fingerprint', ARGV[3], 'kind', ARGV[4],
    'payload', ARGV[5], 'sequence', nextSeq, 'epoch', epoch, 'reserve', tostring(reserve),
    'version', string.format('%.0f', version), 'accepted_ms', string.format('%.0f', now), 'member', ARGV[6], 'auction', ARGV[7])
if op == 'OPEN' then
    redis.call('HSET', KEYS[4], 'available', string.format('%.0f', available - reserve),
        'locked', string.format('%.0f', locked + reserve))
    if reserve > 0 then redis.call('HSET', KEYS[5], 'amount', tostring(reserve)) end
    redis.call('HSET', KEYS[6], 'price', ARGV[8], 'version', string.format('%.0f', version), 'status', 'BID_ONGOING')
    for i = 4, 6 do
        redis.call('HINCRBY', KEYS[i], 'pending', 1)
        redis.call('HINCRBY', KEYS[7], KEYS[i], 1)
        redis.call('PERSIST', KEYS[i])
    end
else
    redis.call('HINCRBY', KEYS[1], 'barriers', 1)
end
redis.call('HSET', KEYS[1], 'seq', nextSeq)
redis.call('HINCRBY', KEYS[1], 'gen', 1)
redis.call('HSET', KEYS[3], 'fingerprint', ARGV[3], 'status', op == 'OPEN' and 'ACCEPTED' or 'PENDING')
return op == 'OPEN' and 'ACCEPTED' or 'PENDING'

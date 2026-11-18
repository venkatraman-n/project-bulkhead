-- Record a transaction and return its velocity features in one atomic round trip.
-- KEYS[1] velocity sorted set (member = transactionId, score = event time ms)
-- KEYS[2] beneficiary hash (field = beneficiaryId, value = first transactionId that paid it)
-- ARGV[1] transactionId   ARGV[2] event time ms   ARGV[3] retention ms (0 = no velocity rules)
-- ARGV[4] beneficiaryId ('' if none)   ARGV[5] beneficiary TTL ms   ARGV[6..n] window lengths ms
-- Returns { newBeneficiary (0/1), count for window 1, count for window 2, ... }
local now = tonumber(ARGV[2])
local retention = tonumber(ARGV[3])
local result = { 0 }

if ARGV[4] ~= '' then
  redis.call('HSETNX', KEYS[2], ARGV[4], ARGV[1])
  redis.call('PEXPIRE', KEYS[2], ARGV[5])
  -- "New" belongs to the first transaction only, so a duplicate of that transaction still sees it as new.
  if redis.call('HGET', KEYS[2], ARGV[4]) == ARGV[1] then
    result[1] = 1
  end
end

if retention > 0 then
  -- NX: a retried transactionId keeps its original entry and is never counted twice.
  redis.call('ZADD', KEYS[1], 'NX', now, ARGV[1])
  redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - retention)
  redis.call('PEXPIRE', KEYS[1], retention)
  for i = 6, #ARGV do
    result[#result + 1] = redis.call('ZCOUNT', KEYS[1], '(' .. (now - tonumber(ARGV[i])), now)
  end
end

return result

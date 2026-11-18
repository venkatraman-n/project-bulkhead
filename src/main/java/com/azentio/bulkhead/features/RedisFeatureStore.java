package com.azentio.bulkhead.features;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis velocity store: one sorted set per tenant + account (member = transactionId, score = event time)
 * and one hash recording which transaction first paid each beneficiary.
 *
 * Everything happens in one Lua script, so it is a single round trip and atomic. ZADD NX keyed by
 * transactionId makes recording idempotent: a retried transaction never counts twice. Keys share the
 * hash tag {tenant:account} so the script works on Redis Cluster.
 */
public class RedisFeatureStore implements FeatureStore {

    static final Duration BENEFICIARY_MEMORY = Duration.ofDays(90);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("redis/record_and_fetch.lua"));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;

    public RedisFeatureStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String velocityKey(String tenantId, String accountToken) {
        return "bh:{" + tenantId + ":" + accountToken + "}:vel";
    }

    static String beneficiaryKey(String tenantId, String accountToken) {
        return "bh:{" + tenantId + ":" + accountToken + "}:ben";
    }

    @Override
    public FeatureSnapshot recordAndFetch(FeatureQuery q) {
        List<String> args = new ArrayList<>();
        args.add(q.transactionId());
        args.add(String.valueOf(q.timestamp().toEpochMilli()));
        args.add(String.valueOf(q.windows().isEmpty() ? 0 : q.retention().toMillis()));
        args.add(Objects.toString(q.beneficiaryId(), ""));
        args.add(String.valueOf(BENEFICIARY_MEMORY.toMillis()));
        q.windows().forEach(w -> args.add(String.valueOf(w.toMillis())));

        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(SCRIPT,
                List.of(velocityKey(q.tenantId(), q.accountToken()), beneficiaryKey(q.tenantId(), q.accountToken())),
                args.toArray());
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Empty reply from velocity script");
        }
        Map<Duration, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < q.windows().size(); i++) {
            counts.put(q.windows().get(i), result.get(i + 1));
        }
        return new FeatureSnapshot(counts, result.get(0) == 1L);
    }
}

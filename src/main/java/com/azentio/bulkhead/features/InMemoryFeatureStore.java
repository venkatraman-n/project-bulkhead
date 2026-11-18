package com.azentio.bulkhead.features;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-node feature store for the walking skeleton and unit tests. Same semantics as the Redis store:
 * history keyed by transactionId (duplicates never count twice), keys namespaced by tenant.
 * Not for production: state is per instance and lost on restart.
 */
public class InMemoryFeatureStore implements FeatureStore {

    /** tenant|account -> (transactionId -> event time millis) */
    private final Map<String, Map<String, Long>> history = new ConcurrentHashMap<>();
    /** tenant|account|beneficiary -> transactionId that first paid this beneficiary */
    private final Map<String, String> firstPayment = new ConcurrentHashMap<>();

    @Override
    public FeatureSnapshot recordAndFetch(FeatureQuery q) {
        Map<Duration, Long> counts = new LinkedHashMap<>();
        if (!q.windows().isEmpty()) {
            long now = q.timestamp().toEpochMilli();
            long oldestKept = now - q.retention().toMillis();
            // compute() locks this account's entry, so concurrent requests for one account are serialised.
            history.compute(q.tenantId() + "|" + q.accountToken(), (key, txns) -> {
                Map<String, Long> events = txns == null ? new HashMap<>() : txns;
                events.putIfAbsent(q.transactionId(), now);
                events.values().removeIf(t -> t <= oldestKept);
                for (Duration window : q.windows()) {
                    long from = now - window.toMillis();
                    counts.put(window, events.values().stream().filter(t -> t > from && t <= now).count());
                }
                return events;
            });
        }

        boolean newBeneficiary = false;
        if (q.beneficiaryId() != null) {
            String key = q.tenantId() + "|" + q.accountToken() + "|" + q.beneficiaryId();
            String owner = firstPayment.putIfAbsent(key, q.transactionId());
            // "New" belongs to the first transaction only, so a duplicate of that transaction still sees it as new.
            newBeneficiary = owner == null || owner.equals(q.transactionId());
        }
        return new FeatureSnapshot(counts, newBeneficiary);
    }
}

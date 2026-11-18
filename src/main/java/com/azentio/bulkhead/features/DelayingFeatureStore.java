package com.azentio.bulkhead.features;

import java.time.Duration;

/**
 * Fault injection: adds fixed latency before delegating. Used by the degraded perf run
 * (bulkhead.features.fault-delay-ms) and by the slow-feature-store tests.
 */
public class DelayingFeatureStore implements FeatureStore {

    private final FeatureStore delegate;
    private final Duration delay;

    public DelayingFeatureStore(FeatureStore delegate, Duration delay) {
        this.delegate = delegate;
        this.delay = delay;
    }

    @Override
    public FeatureSnapshot recordAndFetch(FeatureQuery query) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feature lookup abandoned by its deadline", e);
        }
        return delegate.recordAndFetch(query);
    }
}

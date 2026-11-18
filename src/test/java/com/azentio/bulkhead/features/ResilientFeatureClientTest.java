package com.azentio.bulkhead.features;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Deadline and circuit breaker in isolation, with fault-injecting fakes. No Spring, no Redis. */
class ResilientFeatureClientTest {

    private static final FeatureQuery QUERY = new FeatureQuery("bank-a", "acct-1", null, "txn-1",
            Instant.parse("2026-11-18T06:00:00Z"), List.of(Duration.ofMinutes(10)), Duration.ofMinutes(10));
    private static final FeatureProperties.Breaker BREAKER =
            new FeatureProperties.Breaker(50, 4, 4, 200, 2);

    private ResilientFeatureClient client;

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void slowStore_isAbandonedAtTheDeadline_notAfterItsOwnDelay() {
        client = new ResilientFeatureClient(new DelayingFeatureStore(new InMemoryFeatureStore(), Duration.ofMillis(500)), BREAKER);

        long start = System.nanoTime();
        FeatureLookup lookup = client.fetch(QUERY, Duration.ofMillis(30));

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(150);
        assertThat(lookup.isAvailable()).isFalse();
        assertThat(lookup.unavailableReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void noBudgetLeft_skipsTheCallEntirely() {
        FlakyStore store = new FlakyStore();
        client = new ResilientFeatureClient(store, BREAKER);

        assertThat(client.fetch(QUERY, Duration.ZERO).unavailableReason()).isEqualTo("NO_BUDGET");
        assertThat(store.calls.get()).isZero();
    }

    @Test
    void breakerOpensAfterConfiguredFailures_shortCircuits_thenClosesAfterRecovery() throws Exception {
        FlakyStore store = new FlakyStore();
        client = new ResilientFeatureClient(store, BREAKER);

        store.failing.set(true);
        for (int i = 0; i < 4; i++) {
            assertThat(client.fetch(QUERY, Duration.ofMillis(50)).unavailableReason()).isEqualTo("ERROR");
        }
        assertThat(client.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // While open, the store is not called at all: no time is spent waiting on a dead dependency.
        int callsWhenOpened = store.calls.get();
        assertThat(client.fetch(QUERY, Duration.ofMillis(50)).unavailableReason()).isEqualTo("CIRCUIT_OPEN");
        assertThat(store.calls.get()).isEqualTo(callsWhenOpened);

        // Dependency recovers; after the open wait, probe calls go through and close the breaker.
        store.failing.set(false);
        Thread.sleep(250);
        assertThat(client.fetch(QUERY, Duration.ofMillis(50)).isAvailable()).isTrue();
        assertThat(client.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(client.fetch(QUERY, Duration.ofMillis(50)).isAvailable()).isTrue();
        assertThat(client.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** Fault-injecting fake: throws while "failing" is set, otherwise answers from memory. */
    static class FlakyStore implements FeatureStore {
        final AtomicBoolean failing = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        private final InMemoryFeatureStore delegate = new InMemoryFeatureStore();

        @Override
        public FeatureSnapshot recordAndFetch(FeatureQuery query) {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("Redis connection refused (injected)");
            }
            return delegate.recordAndFetch(query);
        }
    }
}

package com.azentio.bulkhead.features;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0004 recovery position (accept and bound the undercount), for one account before, during and after an
 * outage: transactions scored during the outage are missing from the count, never double counted, and the
 * discrepancy is gone once they age out of the window.
 */
class OutageRecoveryTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-11-17T06:00:00Z");

    private final ResilientFeatureClientTest.FlakyStore store = new ResilientFeatureClientTest.FlakyStore();
    private final ResilientFeatureClient client =
            new ResilientFeatureClient(store, new FeatureProperties.Breaker(50, 50, 100, 1000, 5));

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void undercountDuringOutage_isBoundedByTheWindow_andNeverDoubleCounts() {
        assertThat(count("txn-before", T0)).isEqualTo(1);                          // true count 1

        store.failing.set(true);                                                    // Redis down
        assertThat(client.fetch(query("txn-during", T0.plusSeconds(60)), Duration.ofMillis(50)).isAvailable()).isFalse();
        store.failing.set(false);                                                   // Redis back

        assertThat(count("txn-after", T0.plusSeconds(120))).isEqualTo(2);           // true 3: low by the 1 outage txn
        assertThat(count("txn-after", T0.plusSeconds(120))).isEqualTo(2);           // retry: still not double counted

        // Once the outage transaction (t0+60s) is older than the window, the count is exact again:
        // in (t0+90s, t0+11m30s] the true count is txn-after + txn-late = 2.
        assertThat(count("txn-late", T0.plus(WINDOW).plusSeconds(90))).isEqualTo(2);
    }

    private long count(String txn, Instant at) {
        FeatureLookup lookup = client.fetch(query(txn, at), Duration.ofMillis(50));
        assertThat(lookup.isAvailable()).isTrue();
        return lookup.snapshot().countFor(WINDOW);
    }

    private static FeatureQuery query(String txn, Instant at) {
        return new FeatureQuery("bank-a", "acct-outage", null, txn, at, List.of(WINDOW), WINDOW);
    }
}

package com.azentio.bulkhead.features;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A retried or duplicate transaction never increments velocity twice; windows and tenants are separate. */
class VelocityIdempotencyTest {

    private static final Duration TEN_MIN = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-11-18T06:00:00Z");

    private final InMemoryFeatureStore store = new InMemoryFeatureStore();

    @Test
    void duplicateTransaction_doesNotIncrementVelocityTwice() {
        assertThat(record("bank-a", "txn-1", T0).countFor(TEN_MIN)).isEqualTo(1);
        assertThat(record("bank-a", "txn-1", T0).countFor(TEN_MIN)).isEqualTo(1); // retry
        assertThat(record("bank-a", "txn-1", T0).countFor(TEN_MIN)).isEqualTo(1); // another retry
        assertThat(record("bank-a", "txn-2", T0.plusSeconds(1)).countFor(TEN_MIN)).isEqualTo(2);
    }

    @Test
    void eventsOutsideTheWindow_areNotCounted() {
        record("bank-a", "txn-old", T0);
        assertThat(record("bank-a", "txn-new", T0.plus(TEN_MIN).plusSeconds(1)).countFor(TEN_MIN)).isEqualTo(1);
    }

    @Test
    void sameAccountToken_underAnotherTenant_hasItsOwnCount() {
        record("bank-a", "txn-1", T0);
        record("bank-a", "txn-2", T0);
        assertThat(record("bank-b", "txn-3", T0).countFor(TEN_MIN)).isEqualTo(1);
    }

    private FeatureSnapshot record(String tenant, String txn, Instant at) {
        return store.recordAndFetch(new FeatureQuery(tenant, "acct-1", null, txn, at, List.of(TEN_MIN), TEN_MIN));
    }
}

package com.azentio.bulkhead.screening;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** See docs/architecture/nfr.md for how these split the 80 ms p99 budget. */
@ConfigurationProperties("bulkhead.deadline")
public record DeadlineProperties(long budgetMs, long reserveMs, long maxFeatureTimeoutMs, long duplicateWaitMs) {

    Duration budget() {
        return Duration.ofMillis(budgetMs);
    }

    Duration reserve() {
        return Duration.ofMillis(reserveMs);
    }

    Duration maxFeatureTimeout() {
        return Duration.ofMillis(maxFeatureTimeoutMs);
    }

    /** How long a concurrent duplicate waits for the owner's decision before answering SCREENING_IN_PROGRESS. */
    Duration duplicateWait() {
        return Duration.ofMillis(duplicateWaitMs);
    }
}

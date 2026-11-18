package com.azentio.bulkhead.screening;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** See docs/architecture/nfr.md for how these split the 80 ms p99 budget. */
@ConfigurationProperties("bulkhead.deadline")
public record DeadlineProperties(long budgetMs, long reserveMs, long maxFeatureTimeoutMs) {

    Duration budget() {
        return Duration.ofMillis(budgetMs);
    }

    Duration reserve() {
        return Duration.ofMillis(reserveMs);
    }

    Duration maxFeatureTimeout() {
        return Duration.ofMillis(maxFeatureTimeoutMs);
    }
}

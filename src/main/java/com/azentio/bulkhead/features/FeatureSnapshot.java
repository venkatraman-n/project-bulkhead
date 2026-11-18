package com.azentio.bulkhead.features;

import java.time.Duration;
import java.util.Map;

/**
 * @param velocityCounts transactions for the account inside each window, including this one
 * @param newBeneficiary true if this transaction is the first one seen paying this beneficiary from this account
 */
public record FeatureSnapshot(Map<Duration, Long> velocityCounts, boolean newBeneficiary) {

    public long countFor(Duration window) {
        return velocityCounts.getOrDefault(window, 0L);
    }
}

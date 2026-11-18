package com.azentio.bulkhead.features;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * @param windows   distinct velocity windows the tenant's rules need counts for
 * @param retention how long history must be kept (the tenant's longest window)
 */
public record FeatureQuery(String tenantId, String accountToken, String beneficiaryId, String transactionId,
                           Instant timestamp, List<Duration> windows, Duration retention) {
}

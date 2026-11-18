package com.azentio.bulkhead.features;

/**
 * Result of a deadline-bounded feature lookup.
 *
 * @param snapshot          the features, or null when they could not be obtained in time
 * @param unavailableReason TIMEOUT, ERROR, CIRCUIT_OPEN or NO_BUDGET when snapshot is null
 */
public record FeatureLookup(FeatureSnapshot snapshot, String unavailableReason) {

    static FeatureLookup available(FeatureSnapshot snapshot) {
        return new FeatureLookup(snapshot, null);
    }

    static FeatureLookup unavailable(String reason) {
        return new FeatureLookup(null, reason);
    }

    public boolean isAvailable() {
        return snapshot != null;
    }
}

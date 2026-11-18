package com.azentio.bulkhead.features;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param store        "memory" (default, single node) or "redis"
 * @param faultDelayMs artificial latency added to every lookup; 0 in production, 200 for the degraded perf run
 */
@ConfigurationProperties("bulkhead.features")
public record FeatureProperties(String store, long faultDelayMs, Breaker breaker) {

    public record Breaker(int failureRateThreshold, int minimumCalls, int slidingWindowSize, long waitInOpenMs,
                          int halfOpenCalls) {
    }
}

package com.azentio.bulkhead.features;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Calls the feature store without ever letting it break the request's deadline.
 *
 * - The caller passes the time it can still afford; the lookup runs on a virtual thread and is abandoned
 *   (and interrupted) when that time is up.
 * - A circuit breaker counts timeouts and errors. While it is OPEN, lookups return immediately as
 *   unavailable, so a dead Redis costs ~0 ms instead of the full timeout on every request. After the
 *   wait time it lets a few probe calls through (HALF_OPEN) and closes again when they succeed.
 */
public class ResilientFeatureClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResilientFeatureClient.class);

    private final FeatureStore store;
    private final CircuitBreaker breaker;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientFeatureClient(FeatureStore store, FeatureProperties.Breaker config) {
        this.store = store;
        this.breaker = CircuitBreaker.of("feature-store", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumCalls())
                .failureRateThreshold(config.failureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(config.waitInOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenCalls())
                .build());
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Feature store circuit breaker {}", event.getStateTransition()));
    }

    public FeatureLookup fetch(FeatureQuery query, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            return FeatureLookup.unavailable("NO_BUDGET");
        }
        if (!breaker.tryAcquirePermission()) {
            return FeatureLookup.unavailable("CIRCUIT_OPEN");
        }
        long start = System.nanoTime();
        Future<FeatureSnapshot> call = executor.submit(() -> store.recordAndFetch(query));
        try {
            FeatureSnapshot snapshot = call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return FeatureLookup.available(snapshot);
        } catch (TimeoutException e) {
            call.cancel(true);
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            return FeatureLookup.unavailable("TIMEOUT");
        } catch (ExecutionException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e.getCause());
            log.debug("Feature lookup failed", e.getCause());
            return FeatureLookup.unavailable("ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            breaker.releasePermission();
            return FeatureLookup.unavailable("INTERRUPTED");
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

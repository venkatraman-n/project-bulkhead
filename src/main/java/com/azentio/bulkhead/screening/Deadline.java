package com.azentio.bulkhead.screening;

import java.time.Duration;

/**
 * Per-request time budget. Feature lookups get what is left of the budget after keeping a reserve for
 * rule evaluation and persistence, capped so one slow dependency can never take the whole budget.
 */
public final class Deadline {

    private final long startNanos;
    private final Duration budget;

    private Deadline(long startNanos, Duration budget) {
        this.startNanos = startNanos;
        this.budget = budget;
    }

    public static Deadline start(Duration budget) {
        return new Deadline(System.nanoTime(), budget);
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /** remaining budget - reserve, clamped to [0, cap]. Zero means "no time left: skip the lookup". */
    public Duration featureTimeout(Duration reserve, Duration cap) {
        Duration available = budget.minus(elapsed()).minus(reserve);
        if (available.isNegative()) {
            return Duration.ZERO;
        }
        return available.compareTo(cap) > 0 ? cap : available;
    }
}

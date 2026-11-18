package com.azentio.bulkhead.screening.rules;

/**
 * Outcome of one rule, kept for investigators.
 *
 * @param rule      rule id, also the reason code when it fires
 * @param skipped   true when the rule could not run because its feature was unavailable
 * @param threshold the configured limit, human readable
 * @param observed  the value seen on this transaction, human readable
 * @param points    score contributed (0 unless fired)
 */
public record RuleResult(String rule, boolean fired, boolean skipped, String threshold, String observed, int points) {

    static RuleResult evaluated(String rule, boolean fired, String threshold, String observed, int score) {
        return new RuleResult(rule, fired, false, threshold, observed, fired ? score : 0);
    }

    static RuleResult skipped(String rule, String threshold) {
        return new RuleResult(rule, false, true, threshold, "feature unavailable", 0);
    }
}

package com.azentio.bulkhead.screening.rules;

import com.azentio.bulkhead.screening.Decision;

import java.util.List;

/**
 * @param decision        decision from the tenant's score bands
 * @param missingFeatures features that were unavailable, so their rules were skipped
 */
public record Evaluation(int riskScore, Decision decision, List<RuleResult> results, List<String> missingFeatures) {

    public List<String> firedRules() {
        return results.stream().filter(RuleResult::fired).map(RuleResult::rule).toList();
    }

    public boolean degraded() {
        return !missingFeatures.isEmpty();
    }
}

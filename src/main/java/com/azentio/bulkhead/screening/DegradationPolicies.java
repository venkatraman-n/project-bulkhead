package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.screening.rules.Evaluation;
import com.azentio.bulkhead.tenant.TenantConfig.DegradationPolicy;

import java.math.BigDecimal;

/**
 * Applies a bank's own answer to "what if a feature could not be computed in time?".
 * It only ever makes the decision stricter, and only when features were actually missing; the rules
 * that did run still count normally.
 */
final class DegradationPolicies {

    static final String DEGRADED_REVIEW = "DEGRADED_REVIEW_ABOVE_AMOUNT";
    static final String DEGRADED_DECLINE = "DEGRADED_DECLINE_ABOVE_AMOUNT";

    private DegradationPolicies() {
    }

    /** @param reasonCode extra reason code when the policy changed the outcome, else null */
    record Outcome(Decision decision, String reasonCode) {
    }

    static Outcome apply(DegradationPolicy policy, BigDecimal amount, Evaluation evaluation) {
        if (!evaluation.degraded()) {
            return new Outcome(evaluation.decision(), null);
        }
        boolean above = policy.amount() != null && amount.compareTo(policy.amount()) > 0;
        return switch (policy.policy()) {
            case FAIL_OPEN -> new Outcome(evaluation.decision(), null);
            case REVIEW_ABOVE_AMOUNT -> above
                    ? new Outcome(evaluation.decision().atLeast(Decision.REVIEW), DEGRADED_REVIEW)
                    : new Outcome(evaluation.decision(), null);
            case DECLINE_ABOVE_AMOUNT -> above
                    ? new Outcome(Decision.DECLINE, DEGRADED_DECLINE)
                    : new Outcome(evaluation.decision(), null);
        };
    }
}

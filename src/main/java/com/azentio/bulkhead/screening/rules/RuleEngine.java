package com.azentio.bulkhead.screening.rules;

import com.azentio.bulkhead.api.ScreeningRequest;
import com.azentio.bulkhead.features.FeatureSnapshot;
import com.azentio.bulkhead.screening.Decision;
import com.azentio.bulkhead.tenant.TenantConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Evaluates a tenant's four rule types against one transaction. Pure function: no I/O, no clock,
 * so it is fast, deterministic and trivially unit-testable.
 *
 * A null feature snapshot means the feature store could not answer in time: rules that need it are
 * skipped and reported in missingFeatures, never silently treated as "did not fire".
 */
@Component
public class RuleEngine {

    public static final String AMOUNT_OVER_THRESHOLD = "AMOUNT_OVER_THRESHOLD";
    public static final String HIGH_RISK_COUNTRY = "HIGH_RISK_COUNTRY";
    public static final String NEW_BENEFICIARY_HIGH_AMOUNT = "NEW_BENEFICIARY_HIGH_AMOUNT";
    public static final String FEATURE_VELOCITY = "velocity";
    public static final String FEATURE_NEW_BENEFICIARY = "newBeneficiary";

    public Evaluation evaluate(TenantConfig tenant, ScreeningRequest tx, FeatureSnapshot features) {
        TenantConfig.Rules rules = tenant.rules();
        List<RuleResult> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        var amount = rules.amountThreshold();
        results.add(RuleResult.evaluated(AMOUNT_OVER_THRESHOLD,
                tx.amount().compareTo(amount.threshold()) > 0,
                "> " + amount.threshold().toPlainString(), tx.amount().toPlainString(), amount.score()));

        var country = rules.highRiskCountry();
        results.add(RuleResult.evaluated(HIGH_RISK_COUNTRY,
                country.countries().contains(tx.country()),
                "in " + new TreeSet<>(country.countries()), tx.country(), country.score()));

        var newBeneficiary = rules.newBeneficiaryHighAmount();
        String beneficiaryThreshold = "new beneficiary and amount > " + newBeneficiary.amount().toPlainString();
        if (features == null) {
            results.add(RuleResult.skipped(NEW_BENEFICIARY_HIGH_AMOUNT, beneficiaryThreshold));
            missing.add(FEATURE_NEW_BENEFICIARY);
        } else {
            boolean isNew = tx.beneficiaryId() != null && features.newBeneficiary();
            results.add(RuleResult.evaluated(NEW_BENEFICIARY_HIGH_AMOUNT,
                    isNew && tx.amount().compareTo(newBeneficiary.amount()) > 0,
                    beneficiaryThreshold, "newBeneficiary=" + isNew + ", amount=" + tx.amount().toPlainString(),
                    newBeneficiary.score()));
        }

        for (TenantConfig.Velocity velocity : rules.velocity()) {
            String threshold = "> " + velocity.maxCount() + " in " + velocity.window();
            if (features == null) {
                results.add(RuleResult.skipped(velocity.id(), threshold));
            } else {
                long count = features.countFor(velocity.window());
                results.add(RuleResult.evaluated(velocity.id(), count > velocity.maxCount(),
                        threshold, String.valueOf(count), velocity.score()));
            }
        }
        if (features == null && !rules.velocity().isEmpty()) {
            missing.add(0, FEATURE_VELOCITY);
        }

        int score = Math.min(100, results.stream().mapToInt(RuleResult::points).sum());
        return new Evaluation(score, band(tenant.bands(), score), List.copyOf(results), List.copyOf(missing));
    }

    private static Decision band(TenantConfig.Bands bands, int score) {
        if (score >= bands.declineFrom()) {
            return Decision.DECLINE;
        }
        return score >= bands.reviewFrom() ? Decision.REVIEW : Decision.APPROVE;
    }
}

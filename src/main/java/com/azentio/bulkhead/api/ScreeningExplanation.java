package com.azentio.bulkhead.api;

import com.azentio.bulkhead.screening.Decision;
import com.azentio.bulkhead.screening.Screening;
import com.azentio.bulkhead.screening.rules.RuleResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Why a decision was made, for investigators: every rule with its threshold and observed value, whether
 * features were missing, and which rule-pack version decided. The account token is masked to its last 4.
 */
public record ScreeningExplanation(
        String tenantId,
        String transactionId,
        String accountToken,
        BigDecimal amount,
        String currency,
        String merchantCategory,
        String beneficiaryId,
        String country,
        Decision decision,
        int riskScore,
        List<String> reasonCodes,
        List<RuleResult> rules,
        boolean degraded,
        List<String> missingFeatures,
        String rulePackVersion,
        Instant transactionTimestamp,
        Instant screenedAt,
        int evaluationMs) {

    public static ScreeningExplanation from(Screening s) {
        return new ScreeningExplanation(s.tenantId(), s.transactionId(), mask(s.accountToken()), s.amount(),
                s.currency(), s.merchantCategory(), s.beneficiaryId(), s.country(), s.decision(), s.riskScore(),
                s.reasonCodes(), s.ruleResults(), s.degraded(), s.missingFeatures(), s.rulePackVersion(),
                s.transactionTimestamp(), s.createdAt(), s.evaluationMs());
    }

    /** "acct-12345678" -> "****5678". Tokens are at least 4 characters (validated on ingest). */
    static String mask(String token) {
        return "****" + token.substring(Math.max(0, token.length() - 4));
    }
}

package com.azentio.bulkhead.api;

import com.azentio.bulkhead.screening.Decision;
import com.azentio.bulkhead.screening.Screening;

import java.util.List;

/** What the switch needs to authorise or hold the payment. Built from the stored record, so retries are byte-identical. */
public record ScreeningResponse(
        String tenantId,
        String transactionId,
        Decision decision,
        int riskScore,
        List<String> reasonCodes,
        boolean degraded,
        List<String> missingFeatures,
        String rulePackVersion,
        int evaluationMs) {

    public static ScreeningResponse from(Screening s) {
        return new ScreeningResponse(s.tenantId(), s.transactionId(), s.decision(), s.riskScore(), s.reasonCodes(),
                s.degraded(), s.missingFeatures(), s.rulePackVersion(), s.evaluationMs());
    }
}

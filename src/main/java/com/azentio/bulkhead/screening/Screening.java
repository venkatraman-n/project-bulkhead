package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.screening.rules.RuleResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A scored transaction as stored in the system of record. */
public record Screening(
        UUID id,
        String tenantId,
        String transactionId,
        String payloadHash,
        String accountToken,
        BigDecimal amount,
        String currency,
        String merchantCategory,
        String beneficiaryId,
        String country,
        Instant transactionTimestamp,
        Decision decision,
        int riskScore,
        List<String> reasonCodes,
        List<RuleResult> ruleResults,
        boolean degraded,
        List<String> missingFeatures,
        String rulePackVersion,
        int evaluationMs,
        Instant createdAt) {
}

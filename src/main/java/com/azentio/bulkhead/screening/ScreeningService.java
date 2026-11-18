package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.ScreeningRequest;
import com.azentio.bulkhead.features.FeatureQuery;
import com.azentio.bulkhead.features.FeatureSnapshot;
import com.azentio.bulkhead.features.FeatureStore;
import com.azentio.bulkhead.screening.rules.Evaluation;
import com.azentio.bulkhead.screening.rules.RuleEngine;
import com.azentio.bulkhead.tenant.TenantConfig;
import com.azentio.bulkhead.tenant.TenantRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The scoring path: tenant lookup -> idempotency check -> features -> rules -> persist decision (+ alert).
 *
 * Idempotency is enforced by the database, not by this class: UNIQUE(tenant_id, transaction_id) lets exactly
 * one concurrent duplicate commit; the others read the winner's row and return it unchanged.
 */
@Service
public class ScreeningService {

    private final TenantRegistry tenants;
    private final FeatureStore featureStore;
    private final RuleEngine ruleEngine;
    private final ScreeningRepository repository;
    private final TransactionTemplate transaction;

    public ScreeningService(TenantRegistry tenants, FeatureStore featureStore, RuleEngine ruleEngine,
                            ScreeningRepository repository, TransactionTemplate transaction) {
        this.tenants = tenants;
        this.featureStore = featureStore;
        this.ruleEngine = ruleEngine;
        this.repository = repository;
        this.transaction = transaction;
    }

    public ScreeningOutcome screen(String tenantId, ScreeningRequest request) {
        long startNanos = System.nanoTime();
        TenantConfig tenant = tenants.require(tenantId);
        String payloadHash = PayloadHash.of(request);

        // Fast path for sequential retries: never re-score, never touch the feature store again.
        Optional<Screening> existing = repository.find(tenantId, request.transactionId());
        if (existing.isPresent()) {
            return replay(existing.get(), payloadHash);
        }

        FeatureSnapshot features = featureStore.recordAndFetch(featureQuery(tenantId, tenant, request));
        Evaluation evaluation = ruleEngine.evaluate(tenant, request, features);
        Screening screening = toScreening(tenantId, tenant, request, payloadHash, evaluation, startNanos);

        try {
            // Screening and alert commit together: a losing duplicate leaves neither behind.
            transaction.executeWithoutResult(status -> {
                repository.insert(screening);
                if (screening.decision().raisesAlert()) {
                    repository.insertAlert(screening);
                }
            });
            return new ScreeningOutcome(screening, false);
        } catch (DuplicateKeyException lostRace) {
            Screening winner = repository.find(tenantId, request.transactionId()).orElseThrow(() -> lostRace);
            return replay(winner, payloadHash);
        }
    }

    private static ScreeningOutcome replay(Screening original, String payloadHash) {
        if (!original.payloadHash().equals(payloadHash)) {
            throw new IdempotencyConflictException(original.tenantId(), original.transactionId());
        }
        return new ScreeningOutcome(original, true);
    }

    private static FeatureQuery featureQuery(String tenantId, TenantConfig tenant, ScreeningRequest r) {
        List<Duration> windows = tenant.rules().velocity().stream()
                .map(TenantConfig.Velocity::window).distinct().toList();
        return new FeatureQuery(tenantId, r.accountToken(), r.beneficiaryId(), r.transactionId(), r.timestamp(),
                windows, tenant.longestVelocityWindow());
    }

    private static Screening toScreening(String tenantId, TenantConfig tenant, ScreeningRequest r, String payloadHash,
                                         Evaluation evaluation, long startNanos) {
        int evaluationMs = (int) Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        return new Screening(UUID.randomUUID(), tenantId, r.transactionId(), payloadHash, r.accountToken(),
                r.amount(), r.currency(), r.merchantCategory(), r.beneficiaryId(), r.country(), r.timestamp(),
                evaluation.decision(), evaluation.riskScore(), evaluation.firedRules(), evaluation.results(),
                evaluation.degraded(), evaluation.missingFeatures(), tenant.rulePackVersion(), evaluationMs,
                Instant.now());
    }

    public record ScreeningOutcome(Screening screening, boolean replayed) {
    }
}

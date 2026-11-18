package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.ScreeningRequest;
import com.azentio.bulkhead.api.error.ApiException;
import com.azentio.bulkhead.features.FeatureLookup;
import com.azentio.bulkhead.features.FeatureQuery;
import com.azentio.bulkhead.features.ResilientFeatureClient;
import com.azentio.bulkhead.screening.rules.Evaluation;
import com.azentio.bulkhead.screening.rules.RuleEngine;
import com.azentio.bulkhead.tenant.TenantConfig;
import com.azentio.bulkhead.tenant.TenantRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The scoring path: tenant lookup -> idempotency check -> features (deadline-bounded) -> rules
 * -> tenant degradation policy -> persist decision (+ alert).
 *
 * Idempotency is enforced by the database, not by this class: UNIQUE(tenant_id, transaction_id) lets exactly
 * one concurrent duplicate commit; the others read the winner's row and return it unchanged.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);
    /** A claim this old without a decision means its owner died; another request may take over. */
    static final Duration STALE_CLAIM = Duration.ofSeconds(5);
    static final Duration DUPLICATE_POLL = Duration.ofMillis(5);
    /** Future-dated events would prune current velocity history, so they are rejected beyond normal clock skew. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final TenantRegistry tenants;
    private final ResilientFeatureClient features;
    private final RuleEngine ruleEngine;
    private final ScreeningRepository repository;
    private final TransactionTemplate transaction;
    private final DeadlineProperties deadlines;
    private final MeterRegistry meters;

    public ScreeningService(TenantRegistry tenants, ResilientFeatureClient features, RuleEngine ruleEngine,
                            ScreeningRepository repository, TransactionTemplate transaction,
                            DeadlineProperties deadlines, MeterRegistry meters) {
        this.tenants = tenants;
        this.features = features;
        this.ruleEngine = ruleEngine;
        this.repository = repository;
        this.transaction = transaction;
        this.deadlines = deadlines;
        this.meters = meters;
    }

    public ScreeningOutcome screen(String tenantId, ScreeningRequest request) {
        Deadline deadline = Deadline.start(deadlines.budget());
        TenantConfig tenant = tenants.require(tenantId);
        rejectFutureTimestamp(request);
        String payloadHash = PayloadHash.of(request);
        String txnId = request.transactionId();

        // Fast path for sequential retries: never re-score, never touch the feature store again.
        Optional<Screening> existing = repository.find(tenantId, txnId);
        if (existing.isPresent()) {
            return replay(existing.get(), payloadHash);
        }

        // Ownership before any side effect: only the claim owner, on whichever instance, scores this transaction.
        Instant now = Instant.now();
        if (!repository.tryClaim(tenantId, txnId, payloadHash, now, now.minus(STALE_CLAIM))) {
            return awaitOwner(tenantId, txnId, payloadHash);
        }
        try {
            return scoreAndPersist(tenantId, tenant, request, payloadHash, deadline);
        } catch (RuntimeException failure) {
            try {
                repository.releaseClaim(tenantId, txnId, payloadHash);
            } catch (RuntimeException releaseFailed) {
                failure.addSuppressed(releaseFailed); // claim expires after STALE_CLAIM anyway
            }
            throw failure;
        }
    }

    /**
     * A concurrent duplicate: never score again. A different payload is rejected at once (no feature writes);
     * the same payload waits for the owner's committed decision and returns it unchanged.
     */
    private ScreeningOutcome awaitOwner(String tenantId, String txnId, String payloadHash) {
        Optional<String> claimHash = repository.findClaimHash(tenantId, txnId);
        if (claimHash.isPresent() && !claimHash.get().equals(payloadHash)) {
            throw new IdempotencyConflictException(tenantId, txnId);
        }
        long giveUpAt = System.nanoTime() + deadlines.duplicateWait().toNanos();
        while (System.nanoTime() < giveUpAt) {
            Optional<Screening> decided = repository.find(tenantId, txnId);
            if (decided.isPresent()) {
                return replay(decided.get(), payloadHash);
            }
            try {
                Thread.sleep(DUPLICATE_POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ScreeningInProgressException(tenantId, txnId);
    }

    private static void rejectFutureTimestamp(ScreeningRequest request) {
        if (request.timestamp().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIMESTAMP_IN_FUTURE",
                    "timestamp is more than " + MAX_CLOCK_SKEW.toMinutes() + " minutes in the future");
        }
    }

    private ScreeningOutcome scoreAndPersist(String tenantId, TenantConfig tenant, ScreeningRequest request,
                                             String payloadHash, Deadline deadline) {
        Duration featureTimeout = deadline.featureTimeout(deadlines.reserve(), deadlines.maxFeatureTimeout());
        FeatureLookup lookup = features.fetch(featureQuery(tenantId, tenant, request), featureTimeout);
        Evaluation evaluation = ruleEngine.evaluate(tenant, request, lookup.snapshot());
        DegradationPolicies.Outcome outcome = DegradationPolicies.apply(tenant.degradation(), request.amount(), evaluation);
        if (evaluation.degraded()) {
            recordDegraded(tenantId, tenant, request, evaluation, lookup);
        }
        Screening screening = toScreening(tenantId, tenant, request, payloadHash, evaluation, outcome, deadline);

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
            // Only reachable after a stale-claim takeover raced a slow original owner; the unique key still decides.
            Screening winner = repository.find(tenantId, request.transactionId()).orElseThrow(() -> lostRace);
            return replay(winner, payloadHash);
        }
    }

    /** Investigator read. The tenant is part of the lookup key, so bank-b can never read bank-a's screening. */
    public Screening find(String tenantId, String transactionId) {
        tenants.require(tenantId);
        return repository.find(tenantId, transactionId)
                .orElseThrow(() -> new ScreeningNotFoundException(tenantId, transactionId));
    }

    private void recordDegraded(String tenantId, TenantConfig tenant, ScreeningRequest request,
                                Evaluation evaluation, FeatureLookup lookup) {
        Counter.builder("bulkhead.screenings.degraded")
                .description("Decisions made without all features, per tenant")
                .tag("tenant", tenantId)
                .tag("policy", tenant.degradation().policy().name())
                .register(meters)
                .increment();
        log.warn("Degraded decision tenant={} txn={} reason={} missing={}", tenantId, request.transactionId(),
                lookup.unavailableReason(), evaluation.missingFeatures());
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
                                         Evaluation evaluation, DegradationPolicies.Outcome outcome,
                                         Deadline deadline) {
        List<String> reasonCodes = new ArrayList<>(evaluation.firedRules());
        if (outcome.reasonCode() != null) {
            reasonCodes.add(outcome.reasonCode());
        }
        return new Screening(UUID.randomUUID(), tenantId, r.transactionId(), payloadHash, r.accountToken(),
                r.amount(), r.currency(), r.merchantCategory(), r.beneficiaryId(), r.country(), r.timestamp(),
                outcome.decision(), evaluation.riskScore(), List.copyOf(reasonCodes), evaluation.results(),
                evaluation.degraded(), evaluation.missingFeatures(), tenant.rulePackVersion(),
                (int) deadline.elapsed().toMillis(), Instant.now());
    }

    public record ScreeningOutcome(Screening screening, boolean replayed) {
    }
}

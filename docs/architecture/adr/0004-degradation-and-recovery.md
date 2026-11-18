# ADR 0004: Deadline-bounded feature lookups, per-tenant degradation, bounded undercount after outages

Status: Accepted (2026-11-18)

## Context
- Velocity and new-beneficiary features live in Redis ([nfr.md](../nfr.md) §2.4). Redis can be slow, flapping or down.
- Every bank must still get an answer inside p99 80 ms, and each bank chooses what happens when a feature is missing: let it through, hold large payments for review, or decline large payments.
- Counts must stay correct once Redis recovers: never double counted, and any undercount understood and bounded.

## Options
| Option | Pros | Cons |
|---|---|---|
| A. Fixed Redis timeout (e.g. 50 ms) | Simple | Ignores time already spent; a slow request plus a full timeout breaks the budget |
| B. **Timeout from the remaining budget + circuit breaker + per-tenant policy** | Answer always inside budget; dead Redis costs ~0 ms once the breaker opens; banks keep their own risk appetite | More moving parts; degraded decisions need to be visible |
| C. Fail the request when features are missing | Never decides on partial data | Payments blocked bank-wide by an internal dependency; violates "no bank waits on a slow dependency" |

Recovery of counts for transactions scored while Redis was unavailable:
| Option | Pros | Cons |
|---|---|---|
| R1. Replay degraded screenings from PostgreSQL into Redis on recovery | Exact counts afterwards | Replay competes with live traffic right after an incident; adds a job and ordering concerns |
| R2. **Accept and bound the undercount** | Nothing extra on the hot path or during recovery | Velocity can under-count for at most one window length after an outage |

## Decision
- **Deadline:** each request starts a 60 ms internal budget. A feature lookup gets `remaining - 15 ms reserve`, capped at 25 ms; with nothing left it is skipped (`NO_BUDGET`).
- **Breaker:** Resilience4j, count-based window of 20, opens at 50% failures/timeouts (minimum 10 calls), stays open 2 s, then 3 half-open probes decide. All values are in `bulkhead.features.breaker.*`.
- **Policy:** `degradation.policy` in each tenant file: `FAIL_OPEN` (skip the missing-feature rules), `REVIEW_ABOVE_AMOUNT` or `DECLINE_ABOVE_AMOUNT` with `amount`. Only rules whose features are missing are skipped; the others still score. The policy only makes a decision stricter and adds a `DEGRADED_*` reason code.
- **Visibility:** response and stored row carry `degraded: true` and `missingFeatures`; counter `bulkhead.screenings.degraded{tenant,policy}`; WARN log with the reason (TIMEOUT / ERROR / CIRCUIT_OPEN / NO_BUDGET).
- **Recovery position R2:** transactions scored while Redis was unreachable are not added to velocity history. After recovery, counts can be low by those transactions only, and only until they would have aged out of the window (max: the tenant's longest window, 24 h for bank-c, 10 min for bank-b). Counts are never inflated: recording is keyed by transactionId (`ZADD NX`), so late-arriving or retried writes cannot double count. Degraded rows are flagged in PostgreSQL, so R1 replay can be added later without schema change.

## Consequences
- During an outage, bank-a (FAIL_OPEN) accepts more risk by its own choice; bank-c declines large payments by its own choice.
- An undercount can let a velocity burst through that started during the outage; banks with long windows (bank-c, 24 h) carry the most exposure, which is why bank-c's policy is DECLINE_ABOVE_AMOUNT.
- Lookups abandoned at the deadline may still complete in Redis later; that is harmless because recording is idempotent.

Proven by: src/test/java/com/azentio/bulkhead/screening/DegradedScoringTest.java

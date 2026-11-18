# ADR 0002: Idempotency through a database unique key plus a payload hash

Status: Accepted (2026-11-18)

## Context
- Switches retry on timeouts, sometimes in parallel; the same transaction can arrive several times at once on different instances.
- Requirement: same tenant + transactionId + same payload returns the original decision without re-scoring or a second record; different payload returns 409; another tenant's identical id is a different transaction.

## Options
| Option | Pros | Cons |
|---|---|---|
| A. `UNIQUE(tenant_id, transaction_id)` in PostgreSQL + SHA-256 payload hash column | Correct across any number of instances; atomic with the decision; no extra infrastructure | Losing concurrent duplicates do the scoring work before the insert fails |
| B. Redis `SET NX` lock per key before scoring | Losers stop early | Lock and decision live in different stores: a crash after the lock leaves a stuck key; correctness depends on TTL tuning; Redis outage blocks scoring |
| C. Check-then-insert in Java (`SELECT`, then `INSERT`) | Simple | Race window: two concurrent requests both see "not found" and both insert |
| D. **Claim row first** (`screening_claim` primary key on tenant + transactionId, auto-committed) before scoring, plus option A's unique key on the decision | Concurrent duplicates on any instance are scored once; a conflicting payload is rejected before any feature write | One extra INSERT per new transaction; waiters poll for the owner's decision |

## Decision
Option D (claim first), keeping A's unique key as the final guard.
1. Fast path: `SELECT` the decision by (tenant, transactionId). Found: same hash returns it (`Idempotent-Replayed: true`), different hash returns **409 IDEMPOTENCY_CONFLICT**.
2. `INSERT` a claim (tenant, transactionId, payload hash). Only the request that inserts it scores: feature writes, rule evaluation, degraded metric, then `INSERT` screening + alert in one transaction.
3. A request whose claim insert fails is a concurrent duplicate: different hash → 409 immediately (no feature side effects); same hash → wait (up to `duplicate-wait-ms`) for the owner's decision and return it; still running → 409 `SCREENING_IN_PROGRESS` so the switch retries.
4. If the owner fails before committing, it deletes its claim; if it crashed, the claim can be taken over after 5 s.
- First design (option A alone) let concurrent losers score before losing on the unique key: an independent review reproduced 10 evaluations for 10 simultaneous copies and a polluted velocity count for a conflicting payload. Option D fixes both.
- Tenant is part of the key, so `bank-b/txn-1` and `bank-a/txn-1` never collide.
- The response is always rebuilt from the stored row, so every caller gets an identical body.
- Velocity counting is idempotent separately: the feature store keys history by transactionId ([ADR 0004](0004-degradation-and-recovery.md)).

## Consequences
- Correctness rests on two primary/unique keys (claim and decision), each enforced by the unique index the constraint creates; both hold across many instances or a database sharded by tenant.
- One extra auto-committed INSERT per new transaction (~1-2 ms; see [nfr.md](../nfr.md) §2.2). The perf numbers in [perf-results.md](../perf-results.md) were measured before this change.
- Waiting duplicates poll the decision every 5 ms; this is a rare path (concurrent retries).
- The hash covers business fields only; amount `100` and `100.00` are the same payload.

Proven by: src/test/java/com/azentio/bulkhead/screening/ConcurrentIdempotencyTest.java

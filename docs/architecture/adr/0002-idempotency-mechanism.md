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

## Decision
Option A.
1. Fast path: `SELECT` by (tenant, transactionId). Found: same hash returns the stored decision (`Idempotent-Replayed: true`), different hash returns **409 IDEMPOTENCY_CONFLICT**.
2. Otherwise score, then `INSERT` screening + alert in one transaction.
3. A `DuplicateKeyException` means a concurrent duplicate won: read the winner and apply step 1 to it. The loser's transaction rolls back, so at most one alert exists.
- Tenant is part of the key, so `bank-b/txn-1` and `bank-a/txn-1` never collide.
- The response is always rebuilt from the stored row, so every caller gets an identical body.
- Velocity counting is idempotent separately: the feature store keys history by transactionId ([ADR 0004](0004-degradation-and-recovery.md)).

## Consequences
- Correctness rests on one constraint that also holds after the move to many instances or a sharded database (shard by tenant).
- Concurrent losers waste one scoring pass; acceptable because concurrent duplicates are rare (~5% retries, mostly sequential).
- The hash covers business fields only; amount `100` and `100.00` are the same payload.

Proven by: src/test/java/com/azentio/bulkhead/screening/ConcurrentIdempotencyTest.java

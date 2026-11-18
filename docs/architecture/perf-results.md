# Performance results

Command (service running on :8080): `java scripts/PerfRunner.java --requests 6000 --concurrency 32 --label "<label>"`
Traffic: bank-a 60% / bank-b 25% / bank-c 15%, ~5% replayed duplicates, 2,000 accounts, 300 beneficiaries.
Targets at the HTTP boundary: **p95 40 ms / p99 80 ms** ([nfr.md](nfr.md)). They are targets to report against, not a pass/fail gate.

## Environment

| Item | Value |
|---|---|
| Machine | Laptop, 13th Gen Intel Core i5-1335U (10 cores / 12 threads), 16 GB RAM |
| OS | Windows 11 Home |
| JDK | Temurin 21.0.13, service on virtual threads |
| Database | PostgreSQL 17 (Docker Desktop, `docker-compose.yml`) for the main runs; H2 in-memory for the baseline |
| Feature store | Redis 7 (Docker Desktop) for the main runs; in-memory for the baseline |
| Load generator | Same laptop as the service and both containers, so all compete for the same 12 threads |

## Results (final configuration)

| Run | Requests (dup.) | Errors | Degraded | Throughput | Client p50 / p95 / p99 | Server `evaluationMs` p50 / p95 / p99 |
|---|---|---|---|---|---|---|
| **Normal**: PostgreSQL + Redis | 6,000 (276) | 0 | 1,734 (29%) | 823 req/s | 36.0 / **61.5** / **90.0** ms | 16 / 30 / 40 ms |
| **Degraded**: PostgreSQL + Redis, +200 ms injected on every feature lookup | 6,000 (284) | 0 | 6,000 (100%) | 958 req/s | 29.7 / **57.1** / **88.2** ms | 6 / 20 / 43 ms |
| Baseline: H2 + in-memory features (no Docker hop) | 6,000 (288) | 0 | 25 | 1,829 req/s | 13.0 / **34.5** / **69.0** ms | 3 / 16 / 53 ms |

These runs were taken before the claim-first idempotency fix ([ADR 0002](adr/0002-idempotency-mechanism.md)), which adds one INSERT per new transaction.

`evaluationMs` is measured inside the service from request start to the decision, **before** the INSERT/commit. Client latency is end to end at the HTTP boundary.

Degraded reasons in the normal run (from the WARN log): 248 `TIMEOUT`, 1,380 `CIRCUIT_OPEN`, 5 `NO_BUDGET`. The remaining ~100 degraded responses are replayed duplicates of degraded decisions, which return the stored decision and log nothing new.

## Comparison with targets

| Run | p95 vs 40 ms | p99 vs 80 ms |
|---|---|---|
| Normal (PostgreSQL + Redis) | **missed by 21.5 ms** (61.5) | **missed by 10.0 ms** (90.0) |
| Degraded (+200 ms feature store) | **missed by 17.1 ms** (57.1) | **missed by 8.2 ms** (88.2) |
| Baseline (H2 + in-memory) | met (34.5) | met (69.0) |

What the numbers show:
1. **The deadline works.** With 200 ms injected on every feature lookup, latency is no worse than the normal run, and every request is answered (0 errors, 100% degraded with the bank's policy applied). The slow dependency never reaches the switch.
2. **Our own code path meets the targets.** Without the Docker hops (baseline), p95 34.5 / p99 69.0 ms on a laptop that is also running the load generator.
3. **Persistence most likely dominates the p95 gap** (inferred by subtracting aggregate percentiles, not measured per hop). Server-side time before the write stays at p95 20–30 ms in every run. The client-to-server gap is ~18 ms with H2 in-process (34.5 − 16, which covers Tomcat, JSON, localhost and CPU contention), but ~37 ms with PostgreSQL in Docker (57.1 − 20). The extra ~19 ms is the INSERT + commit to PostgreSQL through Docker Desktop's VM network plus WAL flush on a laptop SSD.
4. **The feature hop is the second cost.** About 4% of Redis lookups (248 of ~5,700) exceeded the 25 ms cap under load, through the same Docker Desktop network. In bursts that pushed the breaker over 50% and short-circuited 1,380 requests (29% degraded in a "healthy" run). The answers stayed fast and correct per policy, but on production hardware this should be near zero.

## What happened on the way (kept because it changed the design)

| Run | Breaker setting | Degraded | Finding |
|---|---|---|---|
| First normal run | window 20, min 10 calls, open 2 s | 5,376 (90%) | Breaker opened on noise |
| Diagnostic rerun with logs | same | 5,932 (99%): 86 `TIMEOUT`, 5,554 `CIRCUIT_OPEN` | Only 1.4% real timeouts; the breaker turned them into 2-second outages, flapping open/closed every ~70 ms of traffic |
| Final normal run | window 100, min 50 calls, open 1 s | 1,734 (29%) | Breaker now needs sustained failure; residual opens come from correlated timeout bursts |

The breaker is meant for sustained failure; sporadic slow calls are already handled by the per-request timeout. This was recorded in [ADR 0004](adr/0004-degradation-and-recovery.md).

## What I would change

| Change | Expected effect |
|---|---|
| Measure per hop with Micrometer timers (ingest, idempotency read, feature lookup, rules, INSERT/commit) instead of inferring from two numbers | Confirms the persistence/feature split above; lets the [per-hop budget](nfr.md) be checked from metrics |
| Rerun with the load generator on another machine and PostgreSQL/Redis on Linux hosts (not Docker Desktop's VM network) | Removes CPU contention and the Windows→VM port-forwarding hop, the main suspects for both gaps |
| Use Lettuce's async API for the feature lookup instead of a blocking call on a virtual thread | Removes a thread hand-off per request and the risk of carrier-thread pinning under load |
| Breaker on a time-based window (e.g. 1 s) and separate slow-call rate | Bursts of timeouts from one GC pause or CPU spike stop opening the breaker |
| Keep the persistence hop to one round trip: insert screening and alert in a single statement (CTE) | Saves one round trip on every REVIEW/DECLINE |
| Capacity: this laptop sustained ~820–960 req/s with everything co-located and ~1,830 req/s on the baseline | Consistent with the ~1,000 TPS per 4-vCPU instance assumed in [nfr.md](nfr.md) §2; recalibrate on server hardware |

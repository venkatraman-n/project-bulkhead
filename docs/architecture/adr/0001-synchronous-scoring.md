# ADR 0001: Score synchronously on the request path, persist before answering

Status: Accepted (2026-11-18)

## Context
- Each bank switch blocks authorisation on our answer and gives us p95 40 ms / p99 80 ms at our HTTP boundary.
- Peak 6,000 TPS combined; bank-a is 60%.
- The answer must be durable: a retried transaction must get the same decision and never be counted twice.

## Options
| Option | Pros | Cons |
|---|---|---|
| A. Synchronous scoring, decision persisted in the same request | One hop; decision is durable before the switch sees it; simple to reason about | DB write latency sits inside the budget |
| B. Async: accept, queue (Kafka), switch polls or gets a callback | Absorbs spikes; DB off the critical path | Switches need a decision inline; polling adds latency and a second failure path; duplicates must still be de-duplicated |
| C. Synchronous scoring, persist asynchronously (fire-and-forget) | Fastest response | A crash between answer and write loses the decision, so a retry is re-scored and velocity re-counted, breaking the idempotency requirement |

## Decision
Option A. Stateless Spring Boot instances on virtual threads score inline. The screening row (and alert, if any) commits in one short transaction before we respond. Side effects that are not needed for the answer (case management, analytics) move to an outbox later ([evolution-plan.md](../evolution-plan.md)).

## Consequences
- The persistence hop gets the largest slice of the budget (p99 30 ms, [nfr.md](../nfr.md)); keep the transaction to two INSERTs, no remote calls inside.
- If PostgreSQL is unavailable we return 503 fast instead of an unrecorded decision ([failure-modes.md](../failure-modes.md)).
- Horizontal scaling is by adding instances; no session state.
- Measured end to end by the committed load script; results in [perf-results.md](../perf-results.md).

Proven by: scripts/PerfRunner.java

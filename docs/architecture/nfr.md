# Non-functional requirements: latency budget and capacity

## 1. Latency budget per hop

Target at our HTTP boundary: **p95 40 ms / p99 80 ms**. The p99 column is what the deadline logic enforces
(`bulkhead.deadline.*` in `application.yml`). Percentiles do not add exactly; the split is a budget each hop is reviewed against.

| Hop | What happens | p95 budget | p99 budget | How it is protected |
|---|---|---|---|---|
| Network | LB to instance and back, inside the region | 5 ms | 10 ms | Keep-alive connections; same region as the switches |
| Ingest / validation | JSON parse, Bean Validation, tenant lookup (in memory), idempotency `SELECT` by unique index | 5 ms | 10 ms | Index lookup only; rule packs held in memory |
| Feature lookup | One Redis round trip (Lua: record + count all windows) | 10 ms | 25 ms | Timeout = remaining budget, capped at 25 ms; circuit breaker ([ADR 0004](adr/0004-degradation-and-recovery.md)) |
| Rule evaluation | 4 rule types, pure in-memory function | 2 ms | 5 ms | No I/O in `RuleEngine` |
| Persistence | claim `INSERT`, then `INSERT` screening (+ alert) and commit | 18 ms | 30 ms | Short statements; `statement_timeout` 50 ms; pool acquire timeout 250 ms (Hikari minimum, above budget: see F3) |
| **Total** | | **40 ms** | **80 ms** | |

## 2. Capacity at 6,000 TPS peak

Assumptions (stated so they can be challenged): traffic split bank-a 60% / bank-b 25% / bank-c 15%; 5% of decisions are REVIEW/DECLINE (alerts); 5% of requests are retries; one 4-vCPU instance sustains about 1,000 TPS inside the latency targets (to be calibrated from [perf-results.md](perf-results.md)).

### 2.1 Instance count
- In-flight requests at peak (Little's law): 6,000 req/s × 0.040 s = **240 concurrent requests**.
- Instances for throughput alone: 6,000 / 1,000 per instance = **6**.
- Requirement: still serve peak + 30% headroom (which also absorbs the ~5% retry reads) **after losing one availability zone**: 6,000 × 1.3 = 7,800 TPS → **8 instances must survive**.
- 2 AZs would need 8 per AZ = 16 instances. **3 AZs × 4 = 12 instances**: losing one AZ leaves 8 × 1,000 = 8,000 ≥ 7,800 TPS. Chosen: **12 at peak**.
- At the 1,500 TPS average: 1,500 × 1.3 = 1,950 TPS must survive an AZ loss → 2 surviving instances → **3 AZs × 1 = 3 instances**.

### 2.2 Database writes per second
- Assumption: 6,000 TPS counts unique payments; switch retries arrive on top (~5% = 300 req/s) and are served as reads by the unique index.
- Per new payment: 1 claim insert (autocommit) + 1 screening insert (+ 1 alert for 5% of decisions) in a second commit.
- Screenings 6,000/s + claims 6,000/s + alerts 6,000 × 5% = 300/s → **peak 12,300 row inserts/s in 12,000 commits/s**.
- Average: 1,500 × 2 + 75 = **3,075 inserts/s**. Claim rows are purged with their day's screening partition.

### 2.3 Rows and storage per day
- Rows per day at the 1,500 TPS average: 1,500 × 86,400 = **129.6 M screenings/day**; alerts 129.6 M × 5% = **6.5 M/day**.
- Screening row ≈ 450 B data + ~300 B `rule_results` JSON + ~150 B indexes ≈ 900 B → 129.6 M × 900 B ≈ **117 GB/day**.
- Alert row ≈ 200 B incl. indexes → 6.5 M × 200 B ≈ **1.3 GB/day**.
- **≈ 118 GB/day**, ≈ 10.6 TB per 90 days → daily partitions on `created_at`, 90 days hot, older partitions archived to object storage.

### 2.4 Redis memory for the velocity windows
- Each transaction is one sorted-set member: transactionId (~40 B) + score (8 B) + skiplist/dict overhead (~60 B) ≈ **110 B**.
- Members kept = tenant TPS × the tenant's longest window. Worst case, a full festival day at 6,000 TPS:

| Tenant | TPS share at peak | Longest window | Members kept | Memory |
|---|---|---|---|---|
| bank-a | 3,600 | 1 h (3,600 s) | 3,600 × 3,600 = 12.96 M | 12.96 M × 110 B ≈ 1.4 GB |
| bank-b | 1,500 | 10 min (600 s) | 1,500 × 600 = 0.9 M | ≈ 0.1 GB |
| bank-c | 900 | 24 h (86,400 s) | 900 × 86,400 = 77.8 M | ≈ 8.6 GB |
| Beneficiary first-seen hashes | | 90 days TTL | ~20 M account-beneficiary pairs × 100 B | ≈ 2.0 GB |
| **Total** | | | | **≈ 12.1 GB** |

- Plan: 3 primaries × 8 GB with a replica each (Redis Cluster; keys use `{tenant:account}` hash tags so one script touches one slot).
- bank-c's 24 h window dominates. If it grows, switch long windows to per-minute buckets (1,440 counters per account) for constant memory per account.

### 2.5 Connection-pool size per instance
- Worst case per instance: peak after losing an AZ = 6,000 / 8 = 750 TPS. DB time per request ≈ 1 ms `SELECT` + 2 ms claim + 4 ms `INSERT`/commit = 7 ms.
- Connections busy on average (Little's law): 750 × 0.007 ≈ **5.3**. p99 DB time ≈ 3× → ~16 busy at spikes.
- **Pool size 20 per instance** (`maximum-pool-size: 20`).
- Fleet: 12 × 20 = 240 server connections. PostgreSQL needs `max_connections` ≥ 300, or PgBouncer in transaction mode in front of it.
- Honest limit: Hikari's connection-acquire timeout cannot go below 250 ms, which is above the 80 ms budget; statements are capped at 50 ms (`statement_timeout`, postgres-redis profile), but database time is not yet derived from the request deadline (see failure mode F3).

## 3. Other NFRs

| NFR | Meaning | How this design addresses it |
|---|---|---|
| Availability | Fraction of requests answered correctly in time | Stateless instances in 3 AZs (survive one AZ loss at peak); Redis failure degrades, not fails ([ADR 0004](adr/0004-degradation-and-recovery.md)); PostgreSQL failure answers 503 fast |
| Idempotency | A repeated request has the effect of one | `UNIQUE(tenant_id, transaction_id)` + payload hash ([ADR 0002](adr/0002-idempotency-mechanism.md)) |
| Tenant isolation | One bank cannot see or disturb another | Tenant in every key and query; per-tenant config; invalid config disables only that tenant ([ADR 0003](adr/0003-tenant-config-model.md)) |
| Auditability | Reconstruct why a decision was made | Every rule's threshold and observed value, rule-pack version, degraded flag stored with the decision |
| PII handling | Limit exposure of customer identifiers | Switch sends tokens, not card numbers; investigator API masks accountToken to the last 4 characters |

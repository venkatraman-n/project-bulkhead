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
| Persistence | `INSERT` screening (+ alert) and commit | 18 ms | 30 ms | Two statements, one transaction, pool timeout 250 ms then 503 |
| **Total** | | **40 ms** | **80 ms** | |

## 2. Capacity at 6,000 TPS peak

Assumptions (stated so they can be challenged): traffic split bank-a 60% / bank-b 25% / bank-c 15%; 5% of decisions are REVIEW/DECLINE (alerts); 5% of requests are retries; one 4-vCPU instance sustains about 1,000 TPS inside the latency targets (to be calibrated from [perf-results.md](perf-results.md)).

### 2.1 Instance count
- In-flight requests at peak (Little's law): 6,000 req/s × 0.040 s = **240 concurrent requests**.
- Instances for throughput: 6,000 / 1,000 per instance = **6**.
- Headroom: survive the loss of one availability zone (2 AZs) and absorb 30% burst: 6 × 1.3 = 7.8, round up per AZ: **2 AZs × 5 = 10 instances** at peak, **4** at the 1,500 TPS average (1,500 / 1,000 × 1.3 × 2 AZs, rounded).

### 2.2 Database writes per second
- Screenings: 6,000 inserts/s (retries are reads, not writes: 6,000 × 5% = 300 reads/s served by the unique index).
- Alerts: 6,000 × 5% = 300 inserts/s, in the same transaction.
- **Peak 6,300 row inserts/s in 6,000 commits/s**; average 1,500 × 1.05 = **1,575 inserts/s**.

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
- Per instance at peak: 6,000 / 10 = 600 TPS. DB time per request ≈ 1 ms `SELECT` + 4 ms `INSERT`/commit = 5 ms.
- Connections busy on average (Little's law): 600 × 0.005 = **3**. p99 DB time ≈ 4× → 12 busy at spikes.
- **Pool size 20 per instance** (`maximum-pool-size: 20`), timeout 250 ms so a slow DB fails fast (503) instead of queueing past the deadline.
- Fleet: 10 × 20 = 200 server connections. PostgreSQL needs `max_connections` ≥ 250, or PgBouncer in transaction mode in front of it.

## 3. Other NFRs

| NFR | Meaning | How this design addresses it |
|---|---|---|
| Availability | Fraction of requests answered correctly in time | Stateless instances in 2 AZs; Redis failure degrades, not fails ([ADR 0004](adr/0004-degradation-and-recovery.md)); PostgreSQL failure answers 503 fast |
| Idempotency | A repeated request has the effect of one | `UNIQUE(tenant_id, transaction_id)` + payload hash ([ADR 0002](adr/0002-idempotency-mechanism.md)) |
| Tenant isolation | One bank cannot see or disturb another | Tenant in every key and query; per-tenant config; invalid config disables only that tenant ([ADR 0003](adr/0003-tenant-config-model.md)) |
| Auditability | Reconstruct why a decision was made | Every rule's threshold and observed value, rule-pack version, degraded flag stored with the decision |
| PII handling | Limit exposure of customer identifiers | Switch sends tokens, not card numbers; investigator API masks accountToken to the last 4 characters |

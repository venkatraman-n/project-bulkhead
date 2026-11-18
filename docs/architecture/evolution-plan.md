# Evolution plan

Three phases, each shippable on its own. Each one names the ADR it touches, its risks and a measurable exit criterion.

## Phase 1: Alerts to case management through a transactional outbox

| | |
|---|---|
| What changes | `alert` rows get an `outbox` companion written in the same transaction as the screening; a relay publishes them to Kafka (topic per region, key = tenant + transactionId); each bank's case management consumes its own tenant's events. The investigator console is built against `docs/api/openapi.yaml`. |
| ADR touched | [ADR 0001](adr/0001-synchronous-scoring.md) (side effects leave the request path), [ADR 0002](adr/0002-idempotency-mechanism.md) (event key = idempotency key, so consumers de-duplicate) |
| Risks | Relay lag hides alerts from investigators; duplicate delivery (Kafka is at-least-once); one bank's slow consumer must not block others (partition by tenant) |
| Exit criterion | 99.9% of alerts visible in case management within 5 s of the decision over a 7-day window; zero lost alerts in a chaos test that kills the relay mid-batch; scoring p99 unchanged (≤ 80 ms) |

## Phase 2: Rule-pack versioning, hot reload and rollback

| | |
|---|---|
| What changes | Rule packs move from files baked into the deployment to a versioned store (Git-backed config service). Instances hot-reload a new version atomically per tenant; every decision already records `rulePackVersion`; rollback = re-point a tenant to the previous version. A pack is validated (same checks as `TenantRegistry`) and replayed against the last 24 h of that tenant's screenings before activation, reporting decision deltas. |
| ADR touched | [ADR 0003](adr/0003-tenant-config-model.md) (onboarding and changes without restart) |
| Risks | Half-applied reload across instances gives two versions live at once; a bad pack activated during peak; replay load on PostgreSQL |
| Exit criterion | A tenant's pack change is live on all instances within 60 s with no restart; rollback in under 60 s; every decision in the system of record carries a version that exists in the store (audited daily, zero orphans) |

## Phase 3: ML score in shadow mode, then champion/challenger

| | |
|---|---|
| What changes | A model score (served in-process or as a sidecar with its own deadline slice) is computed for every transaction but **not used** (shadow); its score and latency are stored next to the rule decision. After review, a per-tenant champion/challenger split routes a configurable share of traffic to the model-backed decision. Missing model score is treated as a missing feature (same degradation policy). |
| ADR touched | [ADR 0004](adr/0004-degradation-and-recovery.md) (deadline slice and degradation now also cover the model), new ADR for model serving |
| Risks | Model latency eats the budget; training/serving skew; a bank's regulators require explainability for model-driven declines |
| Exit criterion | Shadow mode adds ≤ 5 ms to p99 at 6,000 TPS; challenger shows ≥ 10% lower false-positive REVIEW rate at equal fraud catch over 30 days for the pilot bank before promotion |

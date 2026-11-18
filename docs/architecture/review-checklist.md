# Review checklist for PRs on the scoring path

Apply to any PR touching `screening/`, `features/`, `tenant/`, `api/`, migrations or `config/tenants/`.
Each item cites the part of the architecture pack it protects.

| # | Check | Cites |
|---|---|---|
| 1 | No new remote call (HTTP, DB, Redis, Kafka) is added inside the request path without a budget slice in the per-hop table and a deadline-derived timeout. | [nfr.md](nfr.md) §1 latency budget; [ADR 0004](adr/0004-degradation-and-recovery.md) |
| 2 | Every new or changed read/write of screenings, alerts or features includes the tenant id in the key or `WHERE` clause. | [ADR 0003](adr/0003-tenant-config-model.md); failure mode F7 |
| 3 | Writes that must happen once per transaction rely on a database constraint (or `ZADD NX`-style idempotent write), never on check-then-act in Java. | [ADR 0002](adr/0002-idempotency-mechanism.md); failure mode F4 |
| 4 | Responses to the switch are built from the stored row, so a retry is byte-identical; any new response field is persisted too. | [ADR 0002](adr/0002-idempotency-mechanism.md); failure mode F8 |
| 5 | Any rule that needs a feature declares it, is skipped (not treated as "did not fire") when the feature is missing, and appears in `missingFeatures`. | [ADR 0004](adr/0004-degradation-and-recovery.md); failure mode F1 |
| 6 | Rule-pack changes keep `reviewFrom < declineFrom`, set `degradation.amount` for non-FAIL_OPEN policies, bump `rulePackVersion`, and pass `TenantOnboardingTest`-style validation. | [ADR 0003](adr/0003-tenant-config-model.md); failure mode F6 |
| 7 | Transactions stay short: no remote call inside `TransactionTemplate`; at most the screening and alert inserts. | [ADR 0001](adr/0001-synchronous-scoring.md); [nfr.md](nfr.md) §2.5 connection pool |
| 8 | Database failures still map to a fast 503 (no unrecorded decision, no request waiting longer than the pool timeout). | [ADR 0001](adr/0001-synchronous-scoring.md); failure mode F3 |
| 9 | API changes update `docs/api/openapi.yaml` in the same PR and `OpenApiContractTest` passes; account tokens and other identifiers stay masked on investigator responses and out of logs. | [nfr.md](nfr.md) §3 PII handling |
| 10 | Changes to velocity windows or new features include the Redis memory arithmetic for the new retention. | [nfr.md](nfr.md) §2.4 Redis memory; failure mode F10 |

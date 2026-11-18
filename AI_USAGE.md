# AI usage

## Tools
- **Claude (Anthropic), agentic mode with access to my repo folder**: generated most code, tests and documents from my design decisions; I reviewed each change, ran `mvn test` locally after every batch and pushed.
- **Redocly CLI** (not AI) to independently validate `docs/api/openapi.yaml`.

## How I drove it
I owned the decisions; the AI typed. Key prompts and instructions, paraphrased:
1. "Read the assignment and list questions before implementing." Agreed: Java 21 / Spring Boot 3.5, fresh repo (no starter code reused), JDK-only load script because I work on Windows.
2. "Velocity rules must differ per bank: some identical across banks, some bank-specific." Result: velocity rules are a list per tenant (bank-a and bank-b share `VELOCITY_10M`; bank-a adds `VELOCITY_1H`; bank-c has `VELOCITY_5M` + `VELOCITY_24H`). The Redis script counts any number of windows in one round trip.
3. Idempotency: database unique key + payload hash, not a Redis lock ([ADR 0002](docs/architecture/adr/0002-idempotency-mechanism.md)).
4. Degradation: timeout derived from the remaining budget, breaker, per-bank policy, accept-and-bound recovery ([ADR 0004](docs/architecture/adr/0004-degradation-and-recovery.md)).
5. Task-by-task delivery with small Conventional Commits; Task 2 on a feature branch merged by PR.

## Where the AI was wrong or risky, and how it was caught
| Issue | How caught | Fix |
|---|---|---|
| New-beneficiary feature was first designed as "seen before?" check; two concurrent copies of the same transaction could disagree (one sees "new", the other "seen"), making duplicate responses differ | Reviewing the concurrency story for duplicates against the "identical responses" requirement | Store the *first transactionId* per beneficiary (`putIfAbsent` / `HSETNX`): "new" belongs to that transaction, so its duplicates agree |
| First idea for degradation put policy logic inside the rule engine | Review: the engine should stay a pure function and know nothing about policies | `RuleEngine` only marks skipped rules; `DegradationPolicies` applies the bank policy afterwards |
| Spring property binding of `java.nio.file.Path` for the config dir was uncertain | Review before first build | Bound as `String`, resolved with `Path.of` |
| OpenAPI spec failed lint: an unquoted description containing a comma inside a YAML flow map, and no security scheme | Redocly lint | Quoted the description; declared the gateway bearer scheme |
| A file transfer to my machine picked up a cached older archive, so a commit step found no files | `git status` showed nothing to commit | Re-sent under a new name, verified the checksum before committing |
| Testcontainers failed with an empty HTTP 400 from Docker Desktop | `mvn test -Pintegration` on my laptop | Docker Engine 29 rejects the old default API version; pinned `api.version=1.44` in the integration profile |
| First request after startup was always scored degraded: the Redis connection was opened lazily inside the 25 ms feature budget | The real-Redis integration test's "normal" step came back `degraded: true` | `RedisFeatureStore.warmUp()` connects and preloads the Lua script at startup (logs and carries on if Redis is down) |
| Circuit breaker settings proposed by the AI (window 20, min 10 calls, open 2 s) looked reasonable but opened on 1.4% laptop jitter and degraded 90% of a healthy perf run | Perf run showed 5,376 degraded responses; I captured the service log and counted reasons: 5,554 `CIRCUIT_OPEN` vs 86 real timeouts | Retuned to window 100 / min 50 / open 1 s and documented in ADR 0004 and [perf-results.md](docs/architecture/perf-results.md); residual 29% and next steps reported honestly |
| Integration test was first named `*IT`, which Surefire does not run (Failsafe does) | Review of the Maven setup | Renamed to `RedisDegradationIntegrationTest`, still excluded from `mvn test` by its JUnit tag |

## What I verified myself
- Ran `mvn test` after each task on my laptop (Windows, JDK 21) and `mvn test -Pintegration` with Docker.
- Read every class on the scoring path and can explain the idempotency race handling, the Lua script, the deadline arithmetic and the breaker states.
- Ran the load script and recorded the results in [perf-results.md](docs/architecture/perf-results.md).

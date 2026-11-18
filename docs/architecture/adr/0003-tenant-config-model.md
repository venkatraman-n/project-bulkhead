# ADR 0003: Tenant isolation through per-bank rule-pack files and tenant-scoped keys

Status: Accepted (2026-11-18)

## Context
- Three banks today, more later; each wants its own thresholds, velocity windows, score bands and degradation policy.
- Onboarding a fourth bank must be a configuration change, not a release.
- One bank's data or bad configuration must never affect another bank.

## Options
| Option | Pros | Cons |
|---|---|---|
| A. One YAML rule pack per bank in `config/tenants/{tenantId}.yaml`, fixed set of rule types with parameters | Reviewable in a PR, versioned (`rulePackVersion`), validated at load; no DSL to secure | New rule *types* still need code |
| B. Rules in database tables edited through an admin UI | Change without deploy | Needs an admin product, audit and approval flow; harder to review |
| C. Scriptable rules (Groovy/SpEL) per bank | Unlimited flexibility | Code execution from config: security, latency and testability risks on the hot path |
| D. Separate deployment per bank | Strongest isolation | Cost and operations multiply per bank; onboarding becomes a release |

## Decision
Option A, with tenant scoping everywhere:
- **Config:** file name is the tenant id; Bean Validation plus cross-field checks (bands ordered, policy amount present, unique velocity ids) at startup. Banks share rule *types* but choose their own parameters, e.g. bank-a and bank-b share `VELOCITY_10M`, bank-a adds `VELOCITY_1H`, bank-c uses `VELOCITY_5M` + `VELOCITY_24H`.
- **Unknown tenant:** 404 `UNKNOWN_TENANT`. **Invalid file:** that tenant alone gets 503 `TENANT_CONFIG_INVALID`; the others keep scoring.
- **Data:** every key carries the tenant: `UNIQUE(tenant_id, transaction_id)`, feature-store keys `bh:{tenant:account}:...`, read APIs filter by tenant.
- Every decision records the `rulePackVersion` that produced it.

## Consequences
- Adding bank-d = add `bank-d.yaml`, restart or roll the instances. Hot reload is a later step ([evolution-plan.md](../evolution-plan.md)).
- Rule-pack changes go through PR review ([review-checklist.md](../review-checklist.md)).
- Compute is shared, so a noisy tenant can still consume capacity; per-tenant bulkheads are listed as not yet covered in [failure-modes.md](../failure-modes.md).

Proven by: src/test/java/com/azentio/bulkhead/tenant/TenantOnboardingTest.java

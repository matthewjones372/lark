# 0217 — Jobs shared fairly

## Problem

Spec 0216's worker claims the highest priority, then the oldest. One tenant that enqueues ten thousand jobs holds
every other tenant's jobs behind its own until it drains. A kind that calls a payment provider has no way to stay
under the provider's limit across every instance, so a burst gets the service throttled or banned. And a job enqueued
to run now waits up to a whole `poll` before any worker looks.

## Not doing

- No weighted fairness or quotas per tenant over time: a cap per batch only.
- No in-process limiter per worker: a provider's limit is for the service, not the instance.
- No new connection pool: LISTEN holds one connection per worker, from the service's `DataSource`.

## Shape

```kotlin
JobSettings(perTenant = 5)                                     // at most 5 of one tenant's jobs in a claim

object ChargeCard : JobKind<Charge>("charge-card", ChargeCodec, rate = JobRate(10, per = 1.seconds, burst = 20))
```

- **Per tenant:** a claim ranks due jobs within each tenant (`row_number() over (partition by tenant_id …)`) and
  takes at most `perTenant` from each, then orders by priority and run time as before. The ranking reads at most
  `limit × 20` candidates, so a huge backlog costs a bounded scan. Jobs with no tenant count as one tenant.
- **Rate:** a kind with a `JobRate` has one row in `surefold_jobs_buckets` (kind, tokens, refilled_at). A claim for it
  locks the row, refills it from the clock, claims at most `floor(tokens)` jobs, and takes what it claimed, in one
  transaction. Every worker on every instance shares the bucket.
- **Wake-up:** `enqueue` of a job due now also runs `pg_notify('surefold_jobs', kind)`, which Postgres delivers only
  if the caller's transaction commits. Each worker holds one connection that `LISTEN`s, and a notification ends its
  sleep early. The driver's notifications are read through `PGConnection`, found by `unwrap` and reflection, so the
  module still names no driver; on another driver the worker logs once and only polls.

## Why this shape

The bucket lives in Postgres because the limit belongs to the provider, and the worker already holds a transaction
on the claim. **Alternative:** core's in-process `RateLimiter` per worker, which allows `N × rate` across N
instances; recommended against. Reflection for `PGConnection` keeps the "no driver" rule the module was built on.
**Alternative:** depend on `org.postgresql:postgresql`; recommended against, since a service may shade or pin it.

## Stack

- [ ] **`spec-0217-tenants`** — `perTenant` on `JobSettings`, the ranked claim.
      Done when: 100 jobs of tenant A and 5 of tenant B, claimed 10 at a time with `perTenant = 5`, give B all 5 in
      the first claim.
- [ ] **`spec-0217-rate`** — `JobRate`, the buckets table, a claim that takes tokens.
      Done when: two workers draining 100 jobs of a kind limited to 10 a second, with a burst of 10, run at most
      10 + 10 per second elapsed on a `TestClock`.
- [ ] **`spec-0217-notify`** — `pg_notify` on enqueue, a listening connection in the worker.
      Done when: with `poll = 1.minutes`, a job enqueued now runs within a second.

## Acceptance

```bash
./gradlew :surefold-jobs:build :surefold-app-jobs:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Is a per-batch cap enough fairness?** Recommended: yes for now; weighted shares are a later spec if a tenant
   with a steady trickle still waits behind bursts.
2. **Where does a rate live?** Recommended: on the `JobKind`, beside its deadline and concurrency, so one place says
   how a kind is bounded.
3. **Notify for every enqueue, or only one due now?** Recommended: only due now. A job for later is found by polling.

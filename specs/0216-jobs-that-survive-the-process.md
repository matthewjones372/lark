# 0216 — Jobs that survive the process

## Problem

A service that has to do something later, or retry it until it works, has nowhere durable to put it. A reminder
email, a hold that expires, a call to a payment provider that timed out: today each service writes its own table,
its own poller and its own retry, or loses the work when the process dies. Surefold's outbox (spec 0146) is reliable
delivery between actors through the journal, not a queue of work in the service's own database, so it does not
help here.

The work has to commit with the change that caused it. A job enqueued after the business transaction commits is
lost by a crash in between; one enqueued before it runs for a change that rolled back.

## Not doing

This is the first of five specs for `surefold-jobs`. Each later one is one page of its own:

- **0217** fairness and limits: a cap per tenant per batch, token-bucket limits per kind, LISTEN/NOTIFY wake-up.
- **0218** recurring jobs: cron entries deduplicated per instant, and recurrence rules in an IANA zone across DST.
- **0219** long-running ops jobs: cursor in the payload, dry run, progress counters, an audit record.
- **0220** a span per run linked to the enqueuing trace, and a Proofload test of claim throughput.
- Tenancy (`surefold-tenancy`): this spec stores `tenant_id` and nothing reads it but the dedupe key.
- No JSON library. The payload is the handler's codec's text; the module never parses it.
- No exactly-once. Delivery is at least once and the guide says handlers must be idempotent.

## Shape

```kotlin
// in the caller's transaction, on the caller's connection; idempotent on (tenant, kind, dedupeKey)
val enqueued: Boolean = Jobs.enqueue(connection, SendReminder, Reminder(orderId), dedupeKey = "reminder-$orderId",
    runAt = now + 1.hours, expiresAt = now + 2.hours, priority = 5, maxAttempts = 8, tenant = tenantId)
Jobs.cancel(connection, SendReminder, "reminder-$orderId", tenant = tenantId)            // false if not pending
Jobs.reschedule(connection, SendReminder, "reminder-$orderId", runAt = later, tenant = tenantId)

object SendReminder : JobKind<Reminder>("send-reminder", ReminderCodec, deadline = 30.seconds, concurrency = 4)

val handlers = JobHandlers.of(
    SendReminder.handledBy { job -> if (mailer.send(job.payload)) JobResult.Done else JobResult.Retry(5.minutes, "smtp") },
)

val worker: Module = jobWorker(handlers)   // takes DataSource, Migrated and a JobSettings section
```

- **Two modules** in a new `data/` family: `surefold-jobs` on `surefold` alone, and `surefold-app-jobs` for the node,
  on `surefold-app-liquibase` and `surefold-jobs`. No `CHANGELOG.md`; the ticked stack is the record.
- **One table**, `surefold_jobs`, and `surefold_jobs_history`, as `surefold/jobs/postgres.sql`, a formatted-SQL
  changelog the service includes, as the journal ships its own. Columns as briefed: id is a UUIDv7 made in Kotlin
  (Postgres 17 has no generator), payload is `jsonb` beside `payload_version`, and a `carried jsonb` holds
  `Carriers.capture()` from the enqueuing thread, as the journal's metadata does. Unique on
  `(tenant_id, kind, dedupe_key) nulls not distinct`, so a job with no tenant still deduplicates; partial index on
  `(priority desc, run_at) where status = 'pending'`.
- **Claim** is one statement: `update … where id in (select … where status = 'pending' and run_at <= now order by
  priority desc, run_at for update skip locked limit n) returning …`, setting `running`, `locked_until`,
  `locked_by` and `attempts + 1`. A **sweep** returns `running` rows past `locked_until` to `pending`.
- **A handler** declares its kind, its codec, a deadline and a concurrency. It answers `JobResult`:
  `Done`, `Retry(after, reason)`, `Skip(reason)` or `Fail(reason)`. A run past its deadline, or a throw, retries with
  `Schedule.exponential(…).jittered()` until `max_attempts`, then fails. An unknown kind or a payload the codec
  refuses fails at once, without counting an attempt against the handler.
- **Late jobs:** a claimed job past `expires_at` is marked `skipped` and its handler is not called.
- **Finished** rows (`done`, `failed`, `cancelled`, `skipped`) move to history in the transaction that finishes
  them; a sweep deletes history older than `retention`. A finished job frees its dedupe key.
- **The worker** polls every `poll` (default 1s), claims up to the free concurrency, and runs each job on a virtual
  thread under the carried context. `jobWorker` is a node after `Migrated`; on release it stops claiming and waits
  up to `drain` for runs in flight, then leaves the rest to lease expiry.
- **Time** is `clock.get()` everywhere, including what is written to `run_at` and `locked_until`, so a `TestClock`
  moves both.
- **Metrics** through core's `gauge`, `counter` and `histogram`: `surefold.jobs.depth` and `surefold.jobs.lag` per
  kind, `surefold.jobs.runs` tagged by kind and result, `surefold.jobs.duration` per kind. A log line per failure.

## Why this shape

Plain `java.sql` on the service's `DataSource`, as `surefold-actor-journal-jdbc` does: no driver, no pool, no JSON
library in the module, so the classpath gate holds. Enqueue takes a `Connection` rather than opening one, because
that is the only way to share the caller's transaction without owning their transaction API. **Alternative:** a
`transact`-style block the module opens; recommended against, since the service's repository code already has the
connection. Claiming in one `update … returning` keeps a claim to one round trip and lets Postgres, not the worker,
decide who wins.

## Stack

- [x] **`spec-0216-table`** ([#755](https://github.com/matthewjones372/surefold/pull/755)) — module, changelog, UUIDv7, `enqueue`, `cancel`, `reschedule`.
      Done when: enqueueing in a rolled-back transaction leaves no row, and the same key twice leaves one.
- [x] **`spec-0216-claim`** ([#758](https://github.com/matthewjones372/surefold/pull/758)) — claim with lease, sweep, finish into history, retention.
      Done when: two workers claiming 1,000 jobs concurrently never claim the same one, and a lease left by a
      "crashed" worker is claimed again after the `TestClock` passes `locked_until`.
- [ ] **`spec-0216-handlers`** — `JobKind`, `JobResult`, registry, deadline, backoff, expiry, decode failures.
      Done when: a throwing handler is retried with growing delays and fails at `max_attempts`.
- [ ] **`spec-0216-worker`** — the polling worker, per-kind concurrency, and `surefold-app-jobs` with the graph
      node and graceful release.
      Done when: release with a run in flight lets it finish within `drain`, and claims nothing after.
- [ ] **`spec-0216-docs`** — metrics, `docs/jobs.md` with a compiled fence, `modules.md`, `llms.txt`, `SKILL.md`.
      Done when: the `<!-- jobs -->` fence compiles and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-jobs:build :surefold-app-jobs:build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26). Each answer is the question's recommendation.

1. **One module or two?** The node needs `surefold-app-liquibase`, which brings `liquibase-core`. Recommended: two,
   `surefold-jobs` on `surefold` alone and `surefold-app-jobs` for the node, as `surefold-app-actor` sits over
   `surefold-actor`.
2. **Which folder?** Journal-JDBC lives in `actor/`. Recommended: a new `data/` family, since `surefold-files`,
   `surefold-tenancy` and the Pelican stores follow. Alternative: `integrations/`.
3. **Changelog.** The repo has no `CHANGELOG.md`; changes are recorded in specs and PRs. Recommended: add none, and
   let the spec's ticked stack be the entry. Alternative: start a `CHANGELOG.md` with this module.
4. **Does a finished job free its dedupe key?** Recommended: yes, so "remind me again" works; a caller wanting
   once-ever checks history. Alternative: keep the key in history too, unique across both tables.

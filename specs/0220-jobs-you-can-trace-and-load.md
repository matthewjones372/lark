# 0220 — Jobs you can trace and load

## Problem

A job's run is invisible in a trace: its log lines carry the enqueuer's annotations, but no span says it ran, how
long it took or which request asked for it. And nobody has measured how many claims a second the table takes with
several workers, which is the number that decides how many instances a service can run against one Postgres.

## Not doing

- No OpenTelemetry in `surefold-jobs`: a span comes from a small adapter, as `surefold-otel` is to core.
- No published benchmark numbers in the docs until the bench has run on a quiet machine.

## Shape

```kotlin
// surefold-jobs: what a run is, for anything that wants to wrap it. Registered through META-INF/services.
interface JobRunWatcher {
    fun <A> around(run: JobRunInfo, carried: Map<String, String>, block: () -> A): A
}

// surefold-jobs-otel: a span "job send-reminder" per run, a new trace with a link to the enqueuer's span.
```

- **A span per run**, named `job <kind>`, with attributes `surefold.job.id`, `kind`, `tenant`, `attempt` and the
  result. It starts a new trace with a **link** to the enqueuing span, not a parent: a job that runs an hour later in
  another process is not part of the request's latency. The handler's own spans are its children.
- **Proofload:** `bench/surefold-jobs-load` runs N workers against one Postgres and reports claims per second and
  the p99 from due to claimed, at 1, 4 and 16 workers.

## Why this shape

An SPI keeps the module on its one dependency, as `RetryWatcher` keeps core off OpenTelemetry. A link rather than a
parent is OpenTelemetry's own advice for work queued and run later.

## Stack

- [ ] **`spec-0220-watcher`** — `JobRunWatcher` and `JobRunInfo` in `surefold-jobs`, called around each run.
      Done when: a test watcher sees every run's kind, attempt and result, in order.
- [ ] **`spec-0220-otel`** — `surefold-jobs-otel`, a span per run linked to the enqueuer.
      Done when: a job enqueued inside a span runs in a new trace whose root links to it, with the handler's span as
      a child.
- [ ] **`spec-0220-load`** — the Proofload bench.
      Done when: it runs with `./gradlew :surefold-jobs-load:run` and prints claims per second per worker count.

## Acceptance

```bash
./gradlew :surefold-jobs:build :surefold-jobs-otel:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Link or parent?** Recommended: link, as above.
2. **Is Proofload on Maven Central?** If not, the bench builds it from a composite build, or measures with JMH
   instead; decided when the bench is built.

# 0218 — Jobs that recur

## Problem

A service has work on a timetable: prune expired holds every five minutes, send a digest at 08:00 in each tenant's
time zone, remind a customer every Monday until they reply. With spec 0216 it must enqueue each occurrence by hand,
and two instances doing so for the same timetable run it twice. A rule written as "every day at 02:30 Europe/London"
has no 02:30 on the day the clocks go forward and two on the day they go back.

## Not doing

- No cron library: `surefold-jobs` adds nothing to the classpath, so the five-field parser is written here.
- No seconds field, no `L`, `W` or `#`: minute, hour, day of month, month and day of week, with `*`, lists, ranges
  and steps.
- No catch-up: an instance down through three firings runs the next one, not the three it missed.

## Shape

```kotlin
val nightly = Cron.parse("30 2 * * *", ZoneId.of("Europe/London"))
nightly.next(after = instant)                                 // the next firing, as an Instant

// System entries: every worker enqueues the next firing, deduplicated per instant, so any number of instances is safe.
JobWorker(source, handlers, settings, cron = listOf(CronEntry("prune-holds", Cron.parse("*/5 * * * *", UTC), PruneHolds, Unit)))

// A rule per tenant or per customer: finishing one occurrence enqueues the next in the same transaction.
Jobs.enqueueRecurring(connection, SendDigest, Digest(tenantId), dedupeKey = "digest", rule = Cron.parse("0 8 * * *", zone), tenant = tenantId)
```

- **DST:** a firing whose local time does not exist (the clocks went forward over it) runs at the first instant after
  the gap. One that exists twice (the clocks went back) runs once, at the earlier.
- **System entries** are enqueued with the dedupe key `cron:<name>:<instant>`, by each worker's housekeeping, one
  firing ahead. The key makes a second instance's enqueue a no-op.
- **Recurrence rules** are stored on the job, in a new `recurrence` column (`<cron> <zone>`). When an occurrence ends
  `done`, `skipped` or `failed`, the next is enqueued with the same key, payload and tenant, in the transaction that
  moves this one to history. A cancel ends the series.

## Why this shape

One expression language serves both: a cron is a recurrence rule with no series behind it. **Alternative:** RFC 5545
RRULEs for the per-customer rules; more expressive, and a second parser to get right. Recommended: cron for both, and
RRULE later if a rule cron cannot say comes up.

## Stack

- [ ] **`spec-0218-cron`** — `Cron.parse` and `next` in a zone. Pure, no database.
      Done when: "30 2 * * *" in Europe/London fires at 03:30 BST on 2026-03-29 and once, at 01:30 UTC, on 2026-10-25.
- [ ] **`spec-0218-schedules`** — system entries enqueued by the worker's housekeeping.
      Done when: two workers on one `TestClock` across an hour of a "*/5" entry run it 12 times, not 24.
- [ ] **`spec-0218-recurrence`** — the `recurrence` column, `enqueueRecurring`, the next occurrence on finish.
      Done when: a daily 08:00 Europe/London rule run across the October change fires at 07:00 UTC then 08:00 UTC.

## Acceptance

```bash
./gradlew :surefold-jobs:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Does a failed occurrence end the series?** Recommended: no. The next is still enqueued; a series ends by cancel.
2. **Catch up missed firings?** Recommended: no. A digest sent three times on restart is worse than one missed.
3. **Name clash:** core already has `Schedule`. Recommended: call the system entry `CronEntry` instead.

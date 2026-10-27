# 0219 — Jobs that take hours

## Problem

An operator backfills a column over forty million rows, or re-sends a week of confirmations after an outage. A
handler that does it in one run outlives its deadline and its lease, and a crash halfway starts it again from the
top. Nobody can see how far it has got, run it first without writing anything, or say afterwards who started it and
with what.

## Not doing

- No admin UI or HTTP endpoint: those belong to the service, on Pelican. This stores what one would show.
- No pause and resume beyond cancel and enqueue again: the cursor lives in the payload, so a new job can carry it on.

## Shape

```kotlin
object Backfill : JobKind<BackfillCursor>("backfill-totals", CursorCodec, deadline = 1.minutes)

Backfill.handledBy { job ->
    val next = job.payload.after?.let { db.nextBatch(it, 500) } ?: db.firstBatch(500)
    if (next.isEmpty()) return@handledBy JobResult.Done
    if (!job.dryRun) db.write(next)
    JobResult.Continue(BackfillCursor(after = next.last().id), progress = Progress(done = next.size.toLong()))
}

Jobs.startOperation(connection, Backfill, BackfillCursor(after = null), startedBy = "ops:sam", dryRun = true)
Jobs.progress(connection, operationId)    // Progress(done = 12_000, total = null), its status, and who started it
```

- **`JobResult.Continue(payload, after = ZERO, progress)`** writes the new payload and adds the progress to the job's
  counters, fenced like a finish, and returns the job to pending at `after`. It uses no attempt, as `Retry` does not.
- **`dryRun`** is a column, set at start and handed to every run as `job.dryRun`. The handler decides what it skips.
- **Audit:** `startOperation` writes a row to `surefold_jobs_operations` (job id, kind, who, the starting payload,
  dry run, when) in the caller's transaction, and answers its id. The row outlives the job's history.

## Why this shape

The cursor is the payload because the payload is already what survives a crash, a lease and a restart. **Alternative:**
a separate cursor column; recommended against, since it splits one piece of state across two places.

## Stack

- [ ] **`spec-0219-continue`** — `JobResult.Continue`, the payload write, progress counters.
      Done when: a handler that walks 10,000 rows 500 at a time, killed after run 7, finishes from row 3,501.
- [ ] **`spec-0219-operations`** — `dryRun`, `startOperation`, the operations table, `Jobs.progress`.
      Done when: a dry run reports the same progress as the real one and writes nothing the test can see.

## Acceptance

```bash
./gradlew :surefold-jobs:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Is `total` the handler's to set?** Recommended: yes, optional; only the handler knows the size of the work.
2. **Keep operations forever?** Recommended: yes. They are few, and the audit is the point.

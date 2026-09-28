# 0100 — A feed that waits for its writers

## Problem

The JDBC journal's feed reads by `ordering`, which an append takes when it inserts and shows when it commits. At a
missing `ordering` it waited `gapTimeout` (10 s) and then read past, taking the gap for an append that rolled back.
An append whose writer was merely slow to commit, a JVM paused mid-transaction or a pool starved during a database
failover, was then read past too, and when it committed the feed never went back for it: every read model following
the journal missed the event for good. lark-bank's Docker chaos run found it. bank-2, frozen for 20 s with a
transfer's last event inserted and not committed, committed it after the feed had passed its gap; the transfer read
model kept that transfer `Debited` for ever, though the journal and the money were right.

## Not doing

- **No change to offsets.** The feed still reads by `ordering`; nothing that stores a feed offset changes.
- **No change on H2**, which has no transaction snapshots: `gapTimeout` stays its rule there.
- **No going back for an event already passed.** The fix is to not pass it.

## Shape

On Postgres a gap is held until nothing can fill it. The `ordering` was taken by a transaction running when the gap
was first seen, so the feed keeps that moment's `pg_snapshot_xmax(pg_current_snapshot())`, and passes the gap once
the current `pg_snapshot_xmin` has reached it: every transaction running then has ended.

```kotlin
JdbcJournal(dataSource)                                    // Postgres: waits for the writer, however slow
JdbcJournal(dataSource, longestAppend = 10.minutes)         // the most it waits for a transaction that never ends
JdbcJournal(h2, gapTimeout = 10.seconds)                    // H2: unchanged
```

## Why this shape

The horizon is exact about the question the timeout guessed at: whether the append that took the `ordering` can
still commit. A rolled-back append is passed as soon as the transactions around it end, typically within a second
rather than ten; a slow one is waited for. Read-only transactions are given no transaction id and do not hold the
horizon, so a long report does not stall the feed; a writer holding a transaction open does, up to
`longestAppend`, which is the right thing to be alerted on. The alternative, a feed ordered by `(xid, ordering)`,
would never need even the horizon, but changes what an offset is for every reader.

## Stack

- [x] **`spec-0100-horizon`**: the horizon rule on Postgres, [SETTLE] for the moment before an append has its id,
      `longestAppend`, and tests of a slow writer and a rolled-back one.
      Done when: on Postgres an append held open 30 s past a 10 s `gapTimeout` is read once it commits, and a rolled-back
      one is passed within 2 s; both fail on the timeout rule.

## Acceptance

```bash
./gradlew :lark-actor-journal-jdbc:check :lark-actor-projection:check :lark-cluster:check
```

## Open questions

1. **Should `gapTimeout` keep meaning anything on Postgres?** Recommended: no; `longestAppend` bounds the wait
   there, and `gapTimeout` stays for databases without snapshots. Taken.
2. **Should passing a gap at `longestAppend` be louder than a warning?** Recommended: a counter as well, for an
   alert. Left for when the journal gains meters.

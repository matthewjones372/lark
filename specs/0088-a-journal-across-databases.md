# 0088 — A journal across databases

## Problem

`JdbcJournal` writes every event, from every node, into one `lark_journal`
table in one database. Adding nodes spreads entities out, but not their writes:
one Postgres primary is the ceiling for the whole cluster. lark-bank on one
shared 4-CPU machine reaches about 2,700 journal writes a second before that
machine gives out. A tuned primary would go further, but still to a fixed
number, and a transfer costs about five rows.

A service that needs more today has nowhere to go. `Journal` is one object per
flock, and `JournalFeed` answers one `ordering` that the whole database hands
out, so two databases cannot be joined behind them by hand.

## Not doing

- **No moving data between databases.** The number of slices is fixed, so
  adding a database later is a copy, a later spec. Nothing here rebalances.
- **No cross-database transaction.** An append is one id's events, and one id
  lives in one database, so none is needed.
- **No new store.** Cassandra, DynamoDB and distributed SQL are not in scope.
  The routing here is written against `Journal`, so it would sit over one of
  them unchanged.
- **No spreading projections over nodes.** A projection per database is still
  run wherever the service runs it. Moving them around the cluster is a later
  spec.
- **No global order across databases.** Each database's feed keeps its own.

## Shape

```kotlin
val journal = ShardedJournal(
    databases = listOf("db-a" to JdbcJournal(a), "db-b" to JdbcJournal(b)),   // named, so offsets can say which
)
val snapshots = ShardedSnapshots(listOf("db-a" to JdbcSnapshots(a), "db-b" to JdbcSnapshots(b)))
flock { journal(journal); snapshots(snapshots); … }

// One read model is one projection per database, each with its own offset: "statements@db-a", "statements@db-b".
journal.feeds.map { (database, feed) ->
    Projection.follow(feed, "account", AccountEvents, offsets, "statements@$database").runProjecting()
}
```

- **`Slices`**: an id's slice is murmur3 (x86, 32-bit, seed 0) of the UTF-8
  bytes of `"$kind|$id"`, modulo 1,024. The hash is copied into lark and pinned
  by a test of known values, so an id's slice can never change under it. Each
  database owns a contiguous range of slices, in the order given.
- **`ShardedJournal`** is a `Journal` and a `JournalPruning`. `append`, `read`
  and `deleteTo` go to the id's database, so each id's optimistic check, and
  each batched append (0086), stays in one transaction.
- **`feeds: List<Pair<String, JournalFeed>>`**: each database's own feed, gap
  handling (0075) included. There is no merged feed.
- **`ShardedSnapshots`** routes by the same `Slices`. Given the same names in
  the same order, an id's snapshot sits in the same database as its events.
- **`Prune.readTo(id)`**: `Prune` is told whose events it is pruning, and
  defaults to today's `readTo()`. `Prune.after(offsets, journal, names)` reads
  `ShardedJournal.progress(name, database)`, which is `"$name@$database"`, for
  the id's own database, so pruning compares that
  database's offsets with that database's orderings.
- **Offsets** live in one `OffsetStore`. It holds a row per read model per
  database: a few rows, written once a batch.

## Why this shape

An entity is the unit of consistency, so the journal is split by entity and
nothing else changes. Each database keeps its identity-ordered feed and its gap
logic, and a projection per database is the read side's parallelism for free.
A read model that followed one merged feed would need a global sequence, which
is the very bottleneck being removed, or a merge sort that waits on the slowest
database. Read models only ever depended on order within an id, and that
holds.

Fixed slices rather than `hash mod databases`: with mod, adding a database
moves almost every id. With slices, adding one moves only the ranges it takes
over.

## Stack

- [x] **`spec-0088-slices`** — `Slices`, `ShardedJournal` (append, read,
      deleteTo) and `ShardedSnapshots`.
      Done when: on two in-memory journals, an id always lands in the same one,
      a conflict is still a conflict, and 10,000 ids split within 5% of even.
- [ ] **`spec-0088-feeds`** — `feeds`, `Prune.readTo(id)`, and `Prune.after`
      reading the id's own database's offset.
      Done when: two projections over two databases see every event once, and
      pruning in one database waits only on that database's offset.
- [ ] **`spec-0088-postgres`** — the same tests on two embedded Postgres, and
      a `SpreadPersistentBenchmark`, many ids, on one and two databases.
      Done when: two databases write at least 1.6× one for writes spread over
      many ids.

## Acceptance

```bash
./gradlew :lark-actor:check :lark-actor-journal-jdbc:check
./gradlew :lark-actor-benchmarks:jmh -PbenchmarkArgs="SpreadPersistentBenchmark"
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **1,024 slices, fixed?** Yes, as a constant rather than a setting. A setting
   changed after data exists would silently move every id.
2. **`String.hashCode` or murmur3?** Murmur3, copied in and pinned by a test of
   known values. `hashCode` spreads poorly over similar ids.
3. **Does the durable producer's outbox (0085) follow its producer's id?** It
   already does, since it is an id like any other. No change, and a test holds
   it.
4. **How does `Prune` learn the id?** A default `readTo(id)` beside `readTo()`,
   so every existing `Prune` lambda still compiles.
5. **Offsets beside each database, or in one store?** One store for now. No
   read model shares a database with the journal today.

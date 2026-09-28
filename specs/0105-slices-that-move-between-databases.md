# 0105 — Slices that move between databases

## Problem

`ShardedJournal` (spec 0088) keeps each id's events in one of 1,024 slices, and gives each database a contiguous
range: slice × databases ÷ 1024. The ranges depend on how many databases there are. A journal on two databases
that gains a third moves about a third of all ids to a database that holds none of their events. Those entities
recover empty. So a journal's write capacity is fixed on the day it is created. lark-bank found this while
working out how it would scale, after its own config had said "append, never reorder", which is not safe either.

## Not doing

- **Changing the slice count (1,024) or the hash.** Both stay fixed forever.
- **Moving an id to a slice of its own choosing.** Slices move; ids stay in their slice.
- **Moving with no pause at all.** A moving range stops taking appends for its final copy, for seconds.

## Shape

**Routing is a table, not a formula.** Each database owns explicit ranges, kept in a `lark_journal_slices` table
in the first database. Every node reads it at start and whenever its version changes:

```kotlin
ShardedJournal(databases, routing = SliceTable(primary))   // ranges from the table
// db-0: 0..511, db-1: 512..1023   — written once, when the journal is created, by the rule used today
```

**A range moves in four steps,** run by `lark-journal-move` (a small command) or a service's own admin endpoint:
1. **Copy:** the range's events and snapshots are copied to the target, oldest first, while appends continue.
2. **Fence:** the range is marked moving. Appends to it fail with `JournalUnavailable`, which callers already
   retry: an entity answers "try again", as when its database is down.
3. **Catch up and switch:** the tail written since the copy is copied, and the table's version is bumped with the
   range now on the target. Nodes pick up the new version, and appends resume on the target.
4. **Clean up:** after a grace period, the range's rows are deleted from the source.

- **Projections:** events copied to the target reappear in the target's feed. Projections must therefore be
  idempotent by `(id, sequence)`, which `runProjecting` states, and lark-bank's already are. The source's feed
  loses nothing, because rows are deleted only after the grace period.
- Meters: `lark.journal.move{range, step}`.

## Why this shape

The table keeps every existing journal working: its first rows are exactly the ranges the formula gives today. A
move pauses only its own range, for as long as the last copy takes, which is how Pekko's R2DBC plugin splits its
data partitions, but online rather than offline. The alternative, consistent hashing across databases, still moves
data whenever the set changes, and hides from the operator which slices moved.

## Stack

- [ ] **`spec-0105-table`** — `lark_journal_slices`, routing from it, and today's ranges written on first start.
      Done when: a journal created before this spec starts unchanged, and routes every id where it did before.
- [ ] **`spec-0105-move`** — copy, fence, switch and clean up, as a function and a command.
      Done when: under appends from 1,000 entities, a range moves from db-0 to db-2. No append is lost, every
      entity recovers its full history, and appends to other ranges never pause.

## Acceptance

```bash
./gradlew :lark-actor:check :lark-actor-journal-jdbc:check
```

## Open questions

1. **Where does the table live: the first database, or configuration?** Recommended: the first database. Every
   node reads the same version, and a move is one transaction, not a rollout of new config.
2. **How long a grace period before the source's rows are deleted?** Recommended: until every projection's offset
   on the source has passed the range's last copied row, and at least an hour.
3. **Should a node that has not yet seen a new version be able to append to a moved range?** Recommended: no.
   The source database refuses appends to a range it no longer owns, checked in the append's own transaction.

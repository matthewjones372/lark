# 0108 — Appends that share a commit

## Problem

Every `JdbcJournal.append` is a transaction of its own, on a connection of its own: `BEGIN`, the conditional insert,
`COMMIT`, three round trips and one WAL flush for every append, whatever else is appending at that moment. Spec
0086 batches the commands a busy entity has queued into one append, but a service's writes are mostly many entities
with one command each (a bank's transfers touch two accounts and a saga), and those never share anything.

The bank measured it (bank spec 0016, 400 transfers a second on JDK 25): Postgres's backends waited mostly on
`LWLock:WALWrite`, a journal append took 6 to 7 ms against 0.15 ms for a read-model write, and at about eight
appends a transfer the journal, not the JVM, set the rate. Postgres already lets waiting backends share one flush;
what it cannot share is the transaction around each append, the connection held for it, and its round trips.

## Not doing

- **Weakening durability.** An append still answers only once its rows are committed and flushed.
  `synchronous_commit = off` and `commit_delay` are the server's to choose, not the journal's.
- **Changing what an append promises.** The same `Either<JournalConflict, Long>`, the same `SliceElsewhere`, the same
  order within an id.
- **Fewer appends per operation.** How many events a bank's transfer writes is the bank's design, and a spec there.
- **Snapshots and offsets.** They are written far less often than events; they keep a transaction each.

## Shape

**One committer per journal, taking whatever is waiting.** An append joins a queue and waits. The committer takes
every append queued, up to `maxAppends`, runs them in one transaction on one connection, commits once, and answers
each caller. There is no timer: a lone append goes at once, as today; under load, appends that arrive while a commit
is in flight form the next group. The group grows with the load, and a quiet journal pays nothing for it.

```kotlin
val journal = JdbcJournal(dataSource, groupCommit = GroupCommit(maxAppends = 64, committers = 2))
journal.append(id, expected = 3, events)   // unchanged: returns once the group it joined has committed
```

**Each append as it would have been alone.** Inside the group's transaction each append runs under a savepoint. A
conflict (its expected sequence is not the last), a fenced slice, or a duplicate key rolls back to its savepoint and
answers that append alone, while the rest of the group goes on. Two appends for one id in one group run in the order
they queued, so the second sees the first's rows, as it would have after the first had committed.

**A failed commit fails its whole group,** each caller getting the same exception an append of its own would have
thrown, and the next group starting on a fresh connection. No append is answered as written unless its group
committed.

**Committers:** `committers` groups in flight at once, each on its own connection, so a slow commit does not stop
the next group from forming. Two is the default; one serialises every append on a journal.

**Across databases:** a `ShardedJournal` holds one `JdbcJournal` per database, so each database groups its own
appends, and nothing waits on another database's commit.

**The feed is unchanged.** A group is one transaction, so its rows become visible together; the watermark (spec
0100) already reads past transactions still running by their snapshots, and a group is one more such transaction.

## Why this shape

Grouping in the journal, not asking every service to batch, is what reaches the many-entities, one-command-each
load that 0086 cannot. Taking whatever is queued instead of waiting on a timer costs a lone append nothing and needs
no tuning: the group size follows the commit latency by itself. Savepoints keep each append's outcome its own, at
the price of one `SAVEPOINT` and one `RELEASE` per append inside the transaction, which cost no round trip if they
go in the same batch as the insert. The alternative, turning `commit_delay` up on the server, shares flushes that
Postgres already shares and does nothing for the transactions and connections, which is where the time went.

## Depends on

Nothing new. The bank takes it by upgrading Lark and passing `groupCommit`.

## Stack

- [ ] **`spec-0108-committer`** — the queue and committers in `JdbcJournal`, savepoints per append, and failure of
      a whole group on a failed commit.
      Done when: the journal contracts pass with group commit on; a group holding a conflicting append commits the
      rest and answers the conflict alone; a commit failed by a stopped database fails every append in the group
      and none reads back afterwards.
- [ ] **`spec-0108-measured`** — `SpreadPersistentBenchmark` with and without group commit, one and two databases,
      and the bank's 400-a-second profile repeated on it.
      Done when: the numbers are in this spec, and group commit is the default only if it wins at every
      concurrency the benchmark runs.

## Acceptance

```bash
./gradlew build
./gradlew :lark-actor-benchmarks:jmh -Pjmh.includes=SpreadPersistent
```

## Settled

1. **On by default, or asked for?** Asked for: `groupCommit = null` by default, until the benchmark has shown it
   never loses; then on by default in a spec of its own.
2. **`maxAppends`?** 64, to be revisited with the benchmark's numbers.
3. **Committers?** Two per journal, so one commit's flush overlaps the next group's inserts.
4. **Slice moves and prunes through the committer?** No: they are rare and large, and keep their own transactions.

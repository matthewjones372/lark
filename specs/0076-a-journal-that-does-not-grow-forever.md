# 0076 — A journal that does not grow forever

## Problem

0074's snapshots mean a recovery reads only the events after the newest
snapshot, but every event before it is still kept. An entity that has written
a million events keeps a million rows, and the journal's table grows for as
long as the service runs. A service that wants the space back today deletes
rows itself. It can easily delete too much: the event the next append checks
against, or events a snapshot does not yet cover. The first stops the entity
from ever writing again. The second recovers it to the wrong state without a
word.

## Not doing

- **Deleting by age or on a schedule.** Events go when a snapshot makes them
  unnecessary, and only then.
- **Waiting for read models.** A read model (0075) that has not yet read an
  event misses it once it is deleted. A service that prunes a kind a read
  model follows keeps the read model ahead of its snapshots, or does not
  prune. Holding deletion back for read models is its own spec if asked.
- **Changing `Journal`.** Deleting is an interface of its own, so a journal a
  service wrote keeps compiling and prunes nothing.

## Shape

```kotlin
fun order(id: String) = persistent<OrderCommand, OrderEvent, Order>(
    id = PersistenceId("order", id),
    empty = Order.Empty,
    codec = OrderEvent.codec,
    snapshots = every(100, Order.codec, prune = true),   // after a snapshot is saved, delete what the one before covers
    command = { … },
    event = { … },
)
```

- **`JournalPruning`** has `deleteTo(id, sequence)`. It deletes that id's
  events up to and including `sequence`, but never the newest event: the next
  append checks against it. `InMemoryJournal` and `JdbcJournal` implement it.
- **When.** With `prune = true`, once a snapshot at sequence `s` is saved,
  the step deletes every event up to the snapshot before it, at `s - n`. That
  keeps one interval of events as a margin behind the newest snapshot. A
  snapshot that failed to save deletes nothing.
- **A recovery that cannot be right fails.** A start that finds no snapshot,
  or cannot read one, and whose first event is not number 1, fails with an
  error that names the gap. It does not build a state from half a history.
- **Reads.** `read(id)` answers the events that are left, from the first one
  kept. A feed (0075) no longer answers deleted events.

## Why this shape

Tying deletion to snapshots, one interval behind, means an event is only ever
deleted when two snapshots cover it. The cost is keeping `n` more events than
strictly needed. The alternative is Pekko's `withRetention(keepNSnapshots,
deleteEvents)`, which is more to configure for the same result.
Recommended: one flag on `every`.

## Stack

- [x] **`spec-0076-prune`** — `JournalPruning`, the in-memory journal's
      deletion, and `PruneContract` in `lark-actor`'s test fixtures. Done
      when: after a deletion the journal answers only the events kept, still
      appends after the newest, and never deletes the newest.
      ([#195](https://github.com/matthewjones372/lark/pull/195))
- [x] **`spec-0076-retention`** — `prune = true` on `every`, and the refusal
      to recover from half a history. Done when: an actor with 1,050 events
      and snapshots every 100 keeps only events 901 to 1,050 and restarts to
      the same state; a failed save deletes nothing; and one whose snapshot
      store has lost its snapshots fails its start rather than recovering.
      ([#196](https://github.com/matthewjones372/lark/pull/196))
- [x] **`spec-0076-jdbc`** — `JdbcJournal`'s deletion. Done when: it passes
      the contract on H2.
      ([#197](https://github.com/matthewjones372/lark/pull/197))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Keep one snapshot interval behind the newest, or delete everything a
  snapshot covers?** Recommended: keep one interval, so one bad snapshot does
  not lose the history before it.
- **Fail a start that finds a pruned history with no snapshot, or start
  empty?** Recommended: fail, since starting empty silently loses the state.
- **Should pruning wait for read models?** Recommended: not here; a service
  that prunes a kind it projects keeps its projections ahead, and a later
  spec can make pruning wait for named offsets.

Decided (2026-09-27): every open question goes as recommended. Pruning keeps one
snapshot interval of events behind the newest snapshot; a start that finds a
pruned history with no usable snapshot fails; and pruning does not wait for
read models here.

Decided while building `spec-0076-prune`: `InMemoryJournal` now takes an id's
last sequence number from its newest event, not from how many it holds, and
reads by sequence number, since both stop matching once events are deleted.
`PruneContract` holds a journal that is also a feed to its feed losing what
was deleted.

Decided while building `spec-0076-retention`: pruning deletes up to the
sequence number the snapshot reached less `n`, and runs only once that
snapshot is saved; a deletion that throws is logged like a failed save, and
the next snapshot deletes again. A start checks that the first event it reads
follows the snapshot, or is number 1, and throws naming the missing events if
not; under `testActors` that throw comes from `spawn`, as any failed start's does.

Decided while building `spec-0076-jdbc`: a deleted row leaves a missing
`ordering` that 0075's feed would wait on as a gap, so each deletion records
the span of orderings it removed in `lark_journal_pruned`, in the same
transaction, and the feed reads past a missing ordering inside a recorded
span. That is one row per deletion, not per event. The contract's feed test
fails without it.

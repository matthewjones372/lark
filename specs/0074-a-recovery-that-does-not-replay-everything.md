# 0074 — A recovery that does not replay everything

## Problem

0063's persistent behaviour recovers by reading every event it ever wrote and
folding them through `event`. That was fine while an entity lived in one
process and started once. Since 0070 an entity starts again on every move and
every passivation, and since 0072 each start reads its whole history across
the network from a database: an order with ten thousand events pays for ten
thousand rows each time its shard moves. A service today works around it by
keeping state in its own table beside the journal, and loses the guarantee
that the state is exactly what the events built.

## Not doing

- **Deleting events.** A snapshot makes old events unnecessary to recover, not
  unnecessary to keep; the journal stays whole. Deleting up to a snapshot is
  its own spec if asked.
- **Snapshot schema evolution.** A snapshot is the state in the service's own
  codec; a state it can no longer read is a codec problem, as an event is.
- **Snapshots on a schedule or by size.** Every N events only.
- **Snapshots in the journal's table.** They are a store of their own, so a
  journal a service wrote needs no change.

## Shape

```kotlin
fun order(id: String) = persistent<OrderCommand, OrderEvent, Order>(
    id = PersistenceId("order", id),
    empty = Order.Empty,
    codec = OrderEvent.codec,
    snapshots = every(100, Order.codec),   // after each hundredth event, the state as bytes
    command = { … },
    event = { order, event -> order.after(event) },
)

flock<Nothing, Unit> {
    journal(JdbcJournal(dataSource))
    snapshots(JdbcSnapshots(dataSource))   // or InMemorySnapshots(); none means none are taken
    …
}
```

- **`SnapshotStore`** has `save(id, sequence, bytes)` and `latest(id)`. A
  store keeps one snapshot per id: a save replaces an older one and is ignored
  if the store already holds a newer one, so `latest` answers the highest
  sequence number saved.
- **Taking one.** When a persist carries the sequence number across a
  multiple of N, the step saves the state after it, on the step's thread,
  once the events are applied and before `then`.
- **Recovering.** Start loads the latest snapshot, decodes it as the state at
  its sequence number, and replays only the events after it. With no
  snapshot, or no store, it replays everything, as now.
- **A failed save** does not fail the step: the events are already written,
  so the state is safe. It is logged, and the next multiple tries again.
- **JDBC.** `JdbcSnapshots` in `lark-actor-journal-jdbc`, one row per id in a
  `lark_snapshot` table, its DDL beside the journal's.
- **Contract.** `SnapshotContract` in `lark-actor`'s test fixtures, run by the
  in-memory store and the JDBC one, as 0072's journal contract is.

## Why this shape

Every N events keeps the cost of a recovery bounded by N reads and one
snapshot, and the cost of writing one predictable; it is Pekko's
`snapshotWhen`/`withRetention` in its simplest form. A separate store keeps
0063's `Journal` interface unchanged, so every journal written against it,
0072's included, still compiles. The alternative is to let `command` decide
when to snapshot with an effect, which is more flexible and puts a storage
decision in every command. Recommended: every N, set once on the behaviour.

## Stack

- [x] **`spec-0074-store`** — `SnapshotStore`, `InMemorySnapshots`,
      `snapshots(…)` on the flock and in `testActors`, and `SnapshotContract`.
      Done when: the in-memory store passes the contract.
      ([#185](https://github.com/matthewjones372/lark/pull/185))
- [x] **`spec-0074-persistent`** — `every(n, codec)` on `persistent`. Done
      when: an actor with 1,050 events and snapshots every 100 restarts to the
      same state as a full replay, reading only the 50 events after its last
      snapshot; and a store whose saves throw leaves every command answered.
      ([#186](https://github.com/matthewjones372/lark/pull/186))
- [x] **`spec-0074-jdbc`** — `JdbcSnapshots` and its DDL. Done when: it passes
      the contract on H2, and 0072's moved-entity test recovers from a
      snapshot after the move.
      ([#188](https://github.com/matthewjones372/lark/pull/188))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Every N events, or an effect `command` returns?** Recommended: every N,
  as above.
- **Does a failed save fail the step?** Recommended: no; it is logged and
  retried at the next multiple, since the events are already durable.
- **Keep only the latest snapshot per id, or all of them?** Recommended: only
  the latest; an older one is never read.
- **A store of its own, or snapshots on `Journal`?** Recommended: a store of
  its own, so existing journals need no change.

Decided (2026-09-26): every open question goes as recommended. Snapshots are
taken every N events, set once on the behaviour; a failed save is logged and
does not fail the step; a store keeps only the latest snapshot per id; and
snapshots are a store of their own, leaving `Journal` unchanged.

Decided while building `spec-0074-store`: `Ctx.snapshots` is nullable with a
default of null, so a `Ctx` written outside lark still compiles and takes no
snapshots; `testActors` gives an in-memory store by default, as it does a
journal. A store that keeps the last save rather than the newest fails the
contract's replacement and race tests.

Decided while building `spec-0074-persistent`: the state crosses as bytes
through a `StateCodec`, beside `EventCodec`; `snapshots` is the last argument
of `persistent`, so every existing call still compiles. A persist that crosses
a multiple saves the state at the sequence number it reached, not at the
multiple. A failed save is logged as an error with its cause; an interrupt is
not a failed save, and still stops the step.

Decided while building `spec-0074-jdbc`: `lark_snapshot` lives in the same DDL
files as `lark_journal`. A save is one conditional update, `where seq_nr < ?`,
and an insert when no row is there; a save that loses the insert to another
runs the update again, so racing saves leave the newest without a lock. The
moved-entity test snapshots every fourth coin, and the next owner reads the
journal only from the ninth; without a store it reads from the first.

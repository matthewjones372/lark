# 0075 — A read model that follows the journal

## Problem

0063's journal is written one entity at a time and read one entity at a time.
A service that wants anything across entities, such as orders by customer,
today's totals, or a search index, has to write it from inside every command's
`then`. That couples the read model to the write, loses the update if the
process dies between the persist and the `then`, and has no way to rebuild a
read model from the events already written. Pekko's answer is Persistence
Query and Projections: every event of a kind, in one order, as a stream, with
an offset stored once each is handled.

## Not doing

- **Tags, or queries beyond one kind.** A feed is every event of one
  `PersistenceId.kind`. A read model over two kinds follows two feeds.
- **Exactly once.** A handled event's offset is stored after its work, so a
  crash between the two handles it again. Read models are written to be
  idempotent, as Kafka consumers are.
- **Sharding the feed across nodes.** One follower per read model; a
  singleton (0070) is where a service runs it.
- **Changing `Journal`.** The feed is an interface of its own, so a journal a
  service wrote keeps compiling, and gains a feed only if it wants one.

## Shape

```kotlin
// lark-actor-projection
val totals = Projection.follow(journal as JournalFeed, kind = "order", codec = OrderEvent.codec,
                               offsets = JdbcOffsets(dataSource), name = "order-totals")
    .mapOrFail { event -> store.apply(event.id, event.value) }   // idempotent, keyed by event.id and event.sequence
    .runProjecting()

totals.start(Forks())
```

- **`JournalFeed`** has `after(kind, offset, limit)`: up to `limit` events of
  `kind`, each with its offset, its entity id, its sequence number and its
  bytes, in offset order, all with offsets greater than `offset`.
  `InMemoryJournal` and `JdbcJournal` implement it.
- **Offsets.** The in-memory journal counts appends. `JdbcJournal` gains an
  `ordering` identity column. An insert that commits after one with a higher
  `ordering` would be skipped by a reader already past it, so the JDBC feed
  stops at a gap and waits up to `gapTimeout` (10 s by default) for it to fill
  before passing it. A rolled-back append, such as a conflict, leaves a gap
  that never fills.
- **`follow`** is a `Stream<Nothing, Followed<E>>` on any lark-stream
  backend. It polls the feed every `every` (1 s by default) when it has
  caught up, on the run's clock, and never ends by itself.
- **`OffsetStore`** keeps the last handled offset per read model's name:
  `InMemoryOffsets` and `JdbcOffsets`. `runProjecting()` stores each
  element's offset once its work is done, as 0053's `runCommitting` does, and
  `follow` starts after the stored one.

## Why this shape

A feed by kind with a global offset is what every read model needs and what
both an in-memory list and a SQL table can answer. Storing the offset after
the work, and not in the work's own transaction, keeps the read model's store
the service's choice at the cost of at-least-once delivery. The alternative
is Pekko's exactly-once JDBC handler, which runs the offset and the work in
one transaction. It is only possible when the read model lives in the same
database. Recommended: at-least-once, with idempotent handlers.

## Stack

- [ ] **`spec-0075-feed`** — `JournalFeed`, `Followed`, the in-memory feed,
      and `FeedContract` in `lark-actor`'s test fixtures. Done when: the
      in-memory journal passes the contract.
- [ ] **`spec-0075-jdbc-feed`** — the `ordering` column and the JDBC feed
      with gap handling. Done when: it passes the contract on H2, and an
      append whose transaction is still open is not passed until it commits
      or `gapTimeout` runs out.
- [ ] **`spec-0075-follow`** — `lark-actor-projection`: `follow`,
      `OffsetStore`, `InMemoryOffsets` and `runProjecting`. Done when: on
      `TestStreams`, a projection stopped after 30 of 50 events and started
      again handles events 31 to 50, and an event appended while it runs
      reaches it on the next poll.
- [ ] **`spec-0075-offsets-jdbc`** — `JdbcOffsets` and its DDL. Done when: it
      passes an `OffsetContract` on H2.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **At-least-once with idempotent handlers, or exactly-once in one
  transaction?** Recommended: at-least-once, as above.
- **A feed by kind, or by tags the command chooses?** Recommended: by kind;
  tags change what a persist writes, and a kind is already on every event.
- **The JDBC gap: wait and then pass, or wait forever?** Recommended: wait up
  to `gapTimeout` and then pass, logging it. Waiting forever stalls every
  read model on one conflicted append.
- **`JdbcJournal`'s table changes.** 0072 is unreleased, so the DDL gains the
  column in place rather than through a migration. Recommended: yes.

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

- [x] **`spec-0075-feed`** — `JournalFeed`, `Followed`, the in-memory feed,
      and `FeedContract` in `lark-actor`'s test fixtures. Done when: the
      in-memory journal passes the contract.
      ([#190](https://github.com/matthewjones372/lark/pull/190))
- [x] **`spec-0075-jdbc-feed`** — the `ordering` column and the JDBC feed
      with gap handling. Done when: it passes the contract on H2, and an
      append whose transaction is still open is not passed until it commits
      or `gapTimeout` runs out.
      ([#191](https://github.com/matthewjones372/lark/pull/191))
- [x] **`spec-0075-follow`** — `lark-actor-projection`: `follow`,
      `OffsetStore`, `InMemoryOffsets` and `runProjecting`. Done when: on
      `TestStreams`, a projection stopped after 30 of 50 events and started
      again handles events 31 to 50, and an event appended while it runs
      reaches it on the next poll.
      ([#192](https://github.com/matthewjones372/lark/pull/192))
- [x] **`spec-0075-offsets-jdbc`** — `JdbcOffsets` and its DDL. Done when: it
      passes an `OffsetContract` on H2.
      ([#193](https://github.com/matthewjones372/lark/pull/193))

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

Decided (2026-09-27): every open question goes as recommended. Delivery is at
least once, with handlers that are safe to repeat; a feed is by kind; the JDBC
feed waits up to `gapTimeout` at a gap and then passes it, logging it; and
`JdbcJournal`'s table gains `ordering` in place, since 0072 is unreleased.

Decided while building `spec-0075-feed`: an event of the feed is a `FeedEvent`,
with its offset, id, sequence number and bytes; decoding it is the follower's.
The in-memory journal's appends take turns under one lock, so an offset is
never visible before a smaller one, and its offsets count events across every
kind. The contract's follower reads while eight writers append, and must see
each event once, in growing offsets.

Decided while building `spec-0075-jdbc-feed`: `ordering` is one identity
across every kind, so a gap is an `ordering` missing from the whole table. The
feed reads a kind's rows and then the orderings below the last of them; a row
of the kind that committed between the two reads is held back like a gap, or
it would be skipped. The contract's follower caught that as 196 events of 200.
The gap timeout runs on lark's clock, so a test moves it rather than waits.

Decided while building `spec-0075-follow`: `follow` is a `Stream.blocking`
source, and a run's `stop()` interrupts its wait for the next poll. An element
is a `Followed`, which carries its offset through `mapFollowed` and
`mapFollowedOrFail`, as lark-kafka's `Committed` does, and whose body sees the
id and sequence number as well as the value, for idempotency. The tests run on
Forks with a `TestClock`, since a blocking source waits on its own thread.

Decided while building `spec-0075-offsets-jdbc`: `OffsetStore` and
`InMemoryOffsets` moved to `lark-actor`, beside `JournalFeed`, so
`lark-actor-journal-jdbc` implements the store without depending on
`lark-stream`; `OffsetContract` is in `lark-actor`'s test fixtures. A save
replaces the row, or inserts it and replaces it again if another insert won.

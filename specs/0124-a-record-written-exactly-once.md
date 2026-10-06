# 0124 — A record written exactly once

## Problem

0087's `publishRecord` is at-least-once. An offset is committed only after the
output it produced is acknowledged. But a crash between the acknowledgement
and the commit writes that output again. A service whose downstream cannot
tell a duplicate apart needs the output and the offset to be committed
together, which only a Kafka transaction does.

Writing one by hand around `lark-kafka` is easy to get wrong:

- **Group metadata.** A transaction commits offsets with the consumer's group
  metadata (`sendOffsetsToTransaction`). The consumer is not thread-safe, and
  asking it from the stage that commits races its poll.
- **Too many outputs in one transaction.** `publishRecord` keeps up to 256
  sends in flight, so a transaction committed at record N holds outputs of
  records after N. A crash then commits those outputs without their offsets.
- **Two commits.** The consumer loop commits whatever `runCommitting` marked
  handled, and the transaction commits the same offsets again, out of step.

## Not doing

- **No transactions on Pekko's connector.** `Kafka.subscribe`'s records carry
  no group metadata, and a transactional run refuses them.
- **No transactional dead letters.** `divertLefts(deadLetters(...))` writes
  through its own producer, at-least-once, before the record's transaction.
- **No change to `publishRecord`.** It stays at-least-once with a window of
  sends, which is the throughput most services want.
- **No exactly-once across a rebalance at the very end of a run.** See *Why
  this shape*.

## Shape

```kotlin
val orders = Kafka.transactionalProducer(producerProperties, transactionalId = "checkout-1", StringSerializer(), orderSerializer)

Kafka.consume(consumerProperties, Topic("carts"), key = StringDeserializer(), value = carts)
    .mapRecord { cart -> cart.value().checkout() }
    .transacted(orders) { order -> listOf(Topic("orders").record(order.id, order)) }  // Stream<E, Long>
    .restartOnDefect(Schedule.exponential(100.milliseconds))
    .runFold(0L, Long::plus)

// or, with no restart:
    .runTransactionally(orders) { order -> listOf(Topic("orders").record(order.id, order)) }       // Run<E, Long>
```

- **`TransactionalProducer`.**
  - `Kafka.transactionalProducer(properties, transactionalId, key, value)`
    opens a `KafkaProducer` with that `transactional.id`, and calls
    `initTransactions`, which fences an earlier producer with the same id.
  - Its transactions run one at a time.
- **`transacted(producer, batch = 500, within = 100.milliseconds) { a -> records }`.**
  - Each element is how many records one transaction committed.
  - Records are taken by `groupedWithin(batch, within)`, then one
    transaction runs per batch, on `mapPar(1)` because committing one blocks.
  - Each transaction begins, sends every output, sends the batch's offsets
    with the consumer's group metadata, and commits.
  - A failure aborts the transaction and is a defect. `restartOnDefect`
    after it reads again from the last offsets a transaction committed.
- **`runTransactionally(...)`** is `transacted(...)` folded to the count.
- **The consumer commits nothing for these records.** Nothing marks them
  handled, so `Kafka.consume`'s loop has nothing to commit.
- **Group metadata per record.** The loop reads `groupMetadata()` on the
  polling thread after each poll, and each record's `Handle.group()` carries
  that copy.

## Why this shape

**Batches, not a window.** A batch with one transaction committed at a time
means a transaction holds exactly the outputs of the offsets it commits. That
is the whole correctness argument, and `groupedWithin` and `mapPar(1)` already
run on every backend.

The alternative is a window of sends with the commit gated on draining it.
That is what Pekko's connector does in its transactional source. It needs a
gate that the consumer's polling thread and the sending stage both respect,
and on Forks those are the same thread after 0087. One more transaction per
batch, at the default 100 ms, is the price of the simpler design.

**Which group metadata.** A transaction's offsets are committed with the
metadata of the member that polled the batch's first record. So a batch that
straddles a rebalance is refused by the broker rather than committing a
partition this consumer has lost.

A run's source ends before its last batch commits, whether by `take`, a
`stop`, or a restart giving up. Its consumer has then left the group, and the
broker no longer knows that member. That last transaction therefore commits
with the group id alone, which the broker accepts without a generation check.
If the group rebalances in that window, a new owner may read the last batch
again. Holding the consumer open until downstream drains was the alternative,
and a record dropped by `filterRecord` would leave nothing to wait for.

## Stack

- [x] **`spec-0119-transactions`**: `TransactionalProducer`, `transacted`,
      `runTransactionally`, `Handle.group()` and the loop's metadata.
      Done when: `TransactTest` passes on Forks and Pekko.

## Acceptance

```bash
./gradlew :lark-kafka:check :lark-kafka-pekko:check
```

## Open questions

- **What should a record that `mapConcatRecord` split across two batches
  do?** Recommended: commit its offset only with the batch holding its last
  element, which is what the handle already says. Outputs of its earlier
  elements may then commit one transaction ahead of its offset, so a crash
  between the two writes those outputs again. Keeping one record's elements
  in one batch would need a batching operator of its own.
- **Should `transacted` refuse a consumer that is not `read_committed`?**
  Recommended: no. The isolation level matters when the input was itself
  written transactionally, and that is the reader's choice. The docs say so.
- **One transactional producer per run, or shared?** Recommended: one per
  instance, with a stable `transactional.id`, which is what fencing needs.
  `TransactionalProducer` runs one transaction at a time, so two runs sharing
  it wait on each other rather than corrupting a transaction.

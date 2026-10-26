# 0167 — A batch read and a batch written

## Problem

`surefold-kafka` hands a body one record at a time. A service that wants a bulk insert, say, has no way to take a list
of records and still have their offsets committed by `runCommitting`: `groupedWithin` from surefold-stream gives
`List<Committed<A>>`, which nothing commits.

Writing has the opposite gap. `publishRecord` treats a record the producer gives up on as a defect, so the stream
fails and, under `restartOnDefect`, everything since the last commit is sent again, records that were acknowledged
among them. `transacted` aborts its whole batch. There is no way to see which record failed and decide: dead-letter
it, fail, or skip it and commit. ZIO Kafka has this as `produceChunkAsyncWithFailures`.

## Not doing

- **A batch in, a batch out.** The writer emits one result per record, not one per batch (see Why this shape).
- **Retrying inside the writer.** The producer already retries for `delivery.timeout.ms`; what reaches surefold is final.
- **Per-partition omission.** Once a record fails, the rest of its batch is omitted, on any partition, as in ZIO.
- **Changing `publishRecord` or `transacted`.** Both keep their defect behaviour.
- **A dead-letter helper for `PublishFailed`.** `divertLefts` takes any function; a helper can follow.

## Shape

```kotlin
// Read: one element per batch, committed as a whole by runCommitting() or runCommitting(settings).
Kafka.consume(consumerProperties, Topic("orders"), key = StringDeserializer(), value = StringDeserializer())
    .groupedRecord(500, 100.milliseconds)                 // Stream<Nothing, Committed<List<ConsumerRecord<..>>>>
    .mapRecord { records -> db.insertAll(records.map { it.value() }) }
    .runCommitting()

// Write: one result per record, in order; the caller picks the policy with operators surefold already has.
Kafka.consume(consumerProperties, Topic("carts"), key = StringDeserializer(), value = StringDeserializer())
    .publishBatch(orders) { cart -> Topic("orders").record(cart.key(), cart.value()) }
    // Stream<Nothing, Committed<Either<PublishFailed, ConsumerRecord<String, String>>>>
    .absolve()                       // fail: what came before the first Left commits, the run ends Failed
    // .divertLefts { failed -> }    // dead-letter or skip: the run goes on, later offsets commit past it
    .runCommitting()

// On the producer itself: one result per record, in order.
val results: List<Either<PublishFailed, Published>> = orders.publishAll(records)
```

- `groupedRecord(n, within)` is `groupedWithin` with one handle per batch: the highest offset's handle on each
  partition. A record still expanding (its handle null) is left to the batch its last element lands in. The body
  runs with no `kafka.*` log annotations, since a batch is outside any one record's trace (`Carried.kt`).
- The batch handle is a `@KafkaSpi` type `transacted`'s `offsets()` and Pekko's `runCommitting(settings)` unpack.
- `publishAll` hands each record to `send` without waiting, as ZIO does. Once a callback or a `send` reports a
  failure, every record not yet handed over is not sent and comes back `PublishFailed(record, PublishOmitted)`, its
  cause the first failure. Records already handed over keep their own outcome.
- `publishBatch(producer, batch = DEFAULT_BATCH, within = DEFAULT_WITHIN, to)` groups, calls `publishAll` one batch
  at a time off the stream's thread, then emits each input record with its own offset and `Right(value)` or `Left`.

## Why this shape

One result per record makes "only offsets up to the first failure move" fall out of what exists: offsets move as
each record reaches `runCommitting`, so `absolve()` commits every record before the first `Left` and stops there.
The alternative, one element per batch whose handle covers only the prefix, needs new commit logic and is a trap:
the next batch commits past the prefix on the same partition unless the stream stops. The cost is that the batch
boundary is not visible downstream. For the reader, `Committed<List<A>>` keeps `runCommitting` and `mapRecord`
unchanged; `List<Committed<A>>` would need a new `runCommitting` on both backends.

## Stack

- [x] **`spec-0167-grouped-record`** ([#469](https://github.com/matthewjones372/lark/pull/469)) — `groupedRecord`, the batch handle, `offsets()` and Pekko's
      `runCommitting(settings)` unpacking it, the docs' table row and a short section.
      Done when: against `KafkaBroker`, batches over two partitions commit each partition's highest offset on Forks,
      Pekko and TestStreams and through `Kafka.subscribe`; a restart resumes after the last batch committed; a
      batch's body logs no `kafka.offset`.
- [x] **`spec-0167-publish-batch`** ([#470](https://github.com/matthewjones372/lark/pull/470)) — `Producer.publishAll`, `PublishOmitted`, `publishBatch`, the docs' table
      row and "What happens when a write in a batch fails".
      Done when: a record that fails (too large) comes back `Left` with the records after it omitted and in order;
      `absolve()` commits only what came before it; `divertLefts` commits past it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Names.** `groupedRecord`, `publishBatch`, `publishAll`, `PublishOmitted`. Answered: these. `Record` matches
   the other operators, and `publishAll` sits beside `publish`.
2. **Batches in flight in `publishBatch`.** Answered: one at a time, as `transacted`. Omission never has to reason
   across batches, and a batch of 500 already fills the producer.
3. **`publishAll` blocking, or a `CompletableFuture`.** Answered: blocking, beside `publish`; `publishBatch` runs it
   off the stream's thread, as `transacted` runs its commit.

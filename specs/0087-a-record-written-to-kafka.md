# 0087 — A record written to Kafka

## Problem

`lark-kafka` reads Kafka and nothing else. A service that consumes, transforms
and produces holds a `KafkaProducer` of its own, calls `send(...).get()` inside
`mapRecord`, and gets these wrong:

- **One record at a time.** `get()` in the body waits a round trip per record,
  so the producer never fills a batch. Sending without waiting is faster, but
  then the offset can be committed before the output is written, and a crash
  loses it.
- **A failure has no home.** A record the producer gives up on (too large, not
  authorised, not acknowledged within `delivery.timeout.ms`) throws from
  wherever `get()` was, as whatever the client wrapped it in.
- **Dead letters.** 0054 left `divertLefts` a function and promised the
  dead-letter producer to this spec. Every service writes its own, and each
  must remember to wait for the acknowledgement before the bad record's offset
  moves on.

The petshop's `KafkaBus` is one such hand-written producer.

## Not doing

- **No transactions or exactly-once.** `sendOffsetsToTransaction` needs the
  consumer's group metadata inside the producer's transaction, which is a
  change to the consumer loop too. At-least-once, with the offset committed
  only after the output is acknowledged, is what this gives.
- **No Pekko connector producer.** `lark-kafka-pekko` stays a consumer. The
  producer here is `KafkaProducer` behind `mapAsync`, which Pekko runs as it
  runs Forks.
- **No producer per run.** Unlike the consumer, a `KafkaProducer` is
  thread-safe and batches across callers, so one is opened by whoever owns the
  service and shared.
- **No Avro.** A `Serializer` is taken as given, Confluent's included.

## Shape

```kotlin
val orders: Producer<String, Order> =
    Kafka.producer(properties, key = StringSerializer(), value = orderSerializer)  // AutoCloseable, shared

// One record, from anywhere: where it went, or why the producer gave up on it.
orders.publish(Topic("orders").record(order.id, order))      // Either<PublishFailed, Published>

// A stream to a topic: up to inFlight awaiting the broker, each passed on in order once acknowledged.
placed.publishTo(orders) { order -> Topic("orders").record(order.id, order) }

// Consume, transform, produce: an offset is committed only once its output is acknowledged.
Kafka.consume(consumerProperties, Topic("carts"), key = Decoder.string(), value = carts)
    .divertLefts(bytes.deadLetters(Topic("carts.dead")))     // written, and acknowledged, before moving on
    .mapRecord { cart -> cart.value().checkout() }
    .publishRecord(orders) { order -> Topic("orders").record(order.id, order) }
    .runCommitting()
```

- **`Producer<K, V>`** wraps one `KafkaProducer`. `send(record)` answers a
  `CompletableFuture<Published>`; `publish(record)` waits on it and answers
  `Either<PublishFailed, Published>`. A serializer that throws, which the client
  does inside `send` rather than in its callback, lands in the same place.
- **`publishTo(producer, inFlight = 256) { a -> record }`** on any stream, and
  **`publishRecord`** on a stream of `Committed`, are `mapAsync(inFlight)` over
  `send`. The element passes on unchanged, after its acknowledgement and every
  one before it. A record the producer gives up on is a defect, so
  `restartOnDefect` applies, and nothing from it on is committed.
- **`Producer<ByteArray?, ByteArray?>.deadLetters(topic)`** is a
  `(DecodeError) -> Unit` for `divertLefts`: the record's bytes and headers as
  read, plus `lark.dead-letter.topic`, `.partition`, `.offset`, `.part` and
  `.cause`. It returns only once the broker has the letter, and throws what the
  producer gave up with, so the bad record is not committed past an unwritten
  letter.
- **`Topic.record(key, value)`** is a `ProducerRecord` for the topic.

## Why this shape

`mapAsync` is the whole mechanism. Its window keeps up to `inFlight` sends
outstanding, so the producer can batch them, and it answers in the order the
elements came. So an element reaches `runCommitting` only after its own
acknowledgement and every earlier one, and the commit can never overtake the
output. The alternative is a sink with its own acknowledgement-tracking
commit. That would be a second commit path beside the consumer loop's, and
would work only on the backend it was written for. A defect, rather than a
declared failure, for a record the producer gives up on matches the consumer's
side: the client has already retried whatever can be retried, and what is left
wants the run restarted, not a value to route.

Building it showed a stall in Forks' `mapAsync`: it pulled upstream until its
window was full before answering the first stage. A Kafka consumer on a quiet
topic never answers the pull that would fill it, so records that were already
acknowledged were never committed. Upstream now runs on a fork of its own that
starts each stage as its element arrives, as `buffer`'s does. `mapPar` on Forks
pulled the same way, and now starts its bodies from a feeding fork too.

## Stack

- [x] **`spec-0087-produce`** ([#236](https://github.com/matthewjones372/lark/pull/236)) — `Producer`, `publishTo`, `publishRecord`,
      `deadLetters`, and Forks' `mapAsync` fed by a fork.
      Done when: `ProduceTest` passes on Forks and Pekko, and `BlockingTest`
      holds a finished stage passed on while its source blocks.
- [x] **`spec-0087-mappar-fed`** — Forks' `mapPar` passes a finished body on
      while upstream blocks, as `mapAsync` now does.
      Done when: `mapParRecord` over a consumer on a quiet topic commits every
      record it handled.

## Acceptance

```bash
./gradlew :lark-kafka:check :lark-stream-forks:check :lark-stream-parity:check
```

## Open questions

- **Should a record the producer gives up on be a declared failure instead of
  a defect?** Recommended: a defect. `publish` already answers `Either` for a
  caller that wants to route it, and a stream that wants to can use
  `mapParRecordOrFail { orders.publish(...).bind() }`.
- **What should `inFlight` default to?** Recommended: 256. That is enough to
  fill the producer's default batches, and few enough that a stop leaves
  little to send again. With idempotence on, the client's default since Kafka
  3.0, a partition's order holds however many are in flight.
- **Should `publishTo` pass on the element or where it was written?**
  Recommended: the element, as `Hub`'s `publishTo` does, so a stream can
  publish to two topics in a row. `publish` answers `Published` for a caller
  that wants the offset.

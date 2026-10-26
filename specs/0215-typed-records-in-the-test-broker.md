# 0215 — Typed records in the test broker

## Problem

surefold-kafka-test's `KafkaBroker` (spec 0161) sends and reads strings only: `send` and `sendTo` write string
values with no key, and `read` and `readAll` decode keys and values as strings, from partition 0. A service whose
topic carries Avro, raw bytes or numeric keys can't check it with them.

Petshop hits both. Its tests read an outbox topic keyed by `Long` with Avro `ShopEvent` values, read a dead-letter
topic of raw bytes, and write one keyed record of junk bytes to prove a bad record is dead-lettered. So it keeps its
own `KafkaBroker` on Testcontainers with a typed `records` helper and a hand-built producer (petshop#123), which is a
second copy of this module.

## Not doing

- **No change to `send`, `sendTo`, `read` or `readAll`.** Tests that write and read strings keep them.
- **No broker pointed at an outside cluster.** The broker is still the one in the test JVM.
- **No headers, timestamps or explicit partitions on the typed send.** No caller needs them yet; `sendTo` covers a
  partition for strings.

## Shape

```kotlin
kafka.send(Topic("lettered"), "junk", "not avro".toByteArray(), StringSerializer(), ByteArraySerializer())

val onTheTopic: List<ConsumerRecord<Long, ShopEvent>> =
    kafka.records(Topic("shop-events"), LongDeserializer(), ShopEventDeserializer(schemas))
```

- **`send(topic, key, value, keySerializer, valueSerializer)`** writes one record and waits for the broker's ack, as
  `send` does.
- **`records(topic, key, value)`** reads every partition of the topic from its start, with no group, up to the end
  offset each had when it was called. It is done when it reaches those ends, not when a poll comes back empty, so a
  slow broker can't cut it short. It gives up after the module's 30 seconds, as `readAll` does.

## Why this shape

Kafka's own `Serializer` and `Deserializer` are what a service already has for its topics, Confluent's Avro ones
included, so a test passes the same ones and checks the records the service would read. Reading to the end offsets
answers "what is on the topic now" without guessing a count, which a test checking that something happened once
needs.

**Alternative:** generic overloads of `read` and `readAll`. `read` stops at a count and `readAll` reads one partition
at an isolation level, so neither is the call petshop makes. Recommended: one new read beside them.

## Stack

- [x] **`spec-0215-typed-broker`** — `send` with a key and serializers, and `records` with deserializers, on
      `KafkaBroker`, with tests and `docs/kafka.md`.
      Done when: `:surefold-kafka-test:check` passes, and a test reads typed records from more than one partition.

## Acceptance

```bash
./gradlew :surefold-kafka-test:check
```

## Open questions

None. The owner took petshop#123's recommendations (2026-10-26), which fix this shape.

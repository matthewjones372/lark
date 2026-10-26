# 0160 — what a running consumer and producer can tell you

## Problem

A surefold-kafka run hides its `KafkaConsumer`: `Kafka.partitions` is the only question a caller can ask. zio-kafka
answers `assignment`, `position`, `committed`, `beginningOffsets`, `endOffsets`, `listTopics`, `partitionsFor`
and `metrics` on its consumer, and `flush`, `partitionsFor` and `metrics` on its producer. Lag (end offset minus
committed), the number every Kafka dashboard starts from, cannot be read from a surefold run at all.

Meters are spec 0163; this spec is the questions code asks.

## Not doing

- Handing out the `KafkaConsumer`: only the polling thread may touch it, which is what every guarantee in
  `docs/kafka.md` rests on.
- Admin questions about groups other than the run's own: spec 0162.

## Shape

```kotlin
val running = Kafka.consume(…).runCommitting().start(Forks())
running.kafka.assignment()            // Set<TopicPartition>
running.kafka.lag()                   // Map<TopicPartition, Long>, end offset minus committed
running.kafka.metrics()               // the client's own Map<MetricName, Metric>

producer.flush()
producer.partitionsFor(Topic("orders"))
producer.metrics()
```

Each consumer question is posted to the polling thread and answered there, between polls, as a commit is.

## Why this shape

Questions answered by the thread that owns the consumer, rather than a lock around it: the one-thread rule
stays true. A question waits at most one poll timeout (100 ms by default) for its answer. The alternative, a
second consumer that only asks, sees the group but not this member's position.

## Stack

- [x] **`spec-0160-producer`** ([#706](https://github.com/matthewjones372/surefold/pull/706)): `flush`, `partitionsFor`, `metrics` on `Producer`. Done when: tested against the
      test broker.
- [x] **`spec-0160-consumer`** ([#709](https://github.com/matthewjones372/surefold/pull/709)): the questions above, posted to the polling thread. Done when: `lag()` reads 0
      after a run has committed everything, and N with N records unread.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **Where the handle hangs:** `running.kafka`, or a value the caller passes in to be filled? Recommended: a
   value passed in, since `Running` belongs to surefold-stream, which knows nothing of Kafka.
2. **Blocking or `CompletionStage`?** Recommended: blocking, on the caller's virtual thread, as surefold is.
3. **`metrics()` returns the client's own map, all of it?** Recommended: yes; 0163 records a curated set, and
   this is where the rest is read.

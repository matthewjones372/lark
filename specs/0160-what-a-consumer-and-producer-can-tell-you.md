# 0160 — what a running consumer and producer can tell you

## Problem

A lark-kafka run hides its `KafkaConsumer`: `Kafka.partitions` is the only question a caller can ask. zio-kafka
answers `assignment`, `position`, `committed`, `beginningOffsets`, `endOffsets`, `listTopics`, `partitionsFor`
and `metrics` on its consumer, and `flush`, `partitionsFor` and `metrics` on its producer. Lag (end offset minus
committed), the number every Kafka dashboard starts from, cannot be read from a lark run at all.

lark already has `lark-micrometer` and `lark-otel` for a run's own metrics.

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

- [ ] **`spec-0160-producer`**: `flush`, `partitionsFor`, `metrics` on `Producer`. Done when: tested against the
      test broker.
- [ ] **`spec-0160-consumer`**: the questions above, posted to the polling thread. Done when: `lag()` reads 0
      after a run has committed everything, and N with N records unread.
- [ ] **`spec-0160-meters`**: lag and the client's metrics as Micrometer gauges in `lark-micrometer`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where the handle hangs:** `running.kafka`, or a value the caller passes in to be filled? Recommended: a
   value passed in, since `Running` belongs to lark-stream, which knows nothing of Kafka.
2. **Blocking or `CompletionStage`?** Recommended: blocking, on the caller's virtual thread, as lark is.
3. **Meters in this spec or the next?** Recommended: here, as the last entry, so lag is visible as soon as it
   can be read.

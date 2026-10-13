# 0120 — A partition read on its own, to an end

## Problem

`Kafka.consume` reads a whole topic as one member of a group. Kafka chooses which partitions the consumer gets,
and moves them during a rebalance. That suits a service. It does not suit lark-job (spec 0004 in that repository),
whose Kafka connector plans one split per partition, places each split itself, and must read exactly that
partition. A job also needs to replay a topic as data that ends, reading each partition up to the offset that was
its end when the read began. A consumer group can do neither: nothing in lark-kafka assigns a partition, or knows
where one ends.

Without this, lark-job either copies a consumer loop out of lark-kafka, which its own rules forbid, or builds on
`@KafkaSpi`, which is marked as not for pipelines.

## Not doing

- **Changing `Kafka.consume`.** Group consumption stays as it is.
- **Seeking by time.** `offsetsForTimes` can follow if a caller needs it.
- **Transactions or exactly-once.** Commits are what `consume` already does: after the work, on `runCommitting`.

## Shape

```kotlin
Kafka.partitions(properties, Topic("clicks"))                        // [0, 1, 2, ...]: what lark-job plans from

Kafka.read(
    properties, Topic("clicks"), partition = 3, key = StringDeserializer(), value = clickDeserializer,
    from = From.Committed,        // or From.Earliest, From.Latest, From.Offset(1_234)
    until = Until.Never,          // or Until.EndAtStart: the partition's end offset when the read opens
)                                 // Stream<Nothing, Committed<ConsumerRecord<K, V>>>, ended on runCommitting
```

- **`assign`, never `subscribe`.** No rebalance can take the partition away, so the loop needs no revocation
  handling, and `Committed`, `Handle` and `runCommitting` work exactly as they do for `consume`.
- **Commits go to the `group.id` in `properties`.** Without one, nothing is committed and `From.Committed` is
  refused when the stream is built, with a message saying why.
- **`Until.EndAtStart`** reads `endOffsets` when the read opens, and ends the stream once the consumer's position
  reaches it. A partition already at its end gives an empty stream at once, with no poll timeout.
- **A `Decoder` overload**, as `consume` has, so a record that cannot be decoded is a `Left` that keeps its offset.

## Why this shape

One partition per read is the unit a job engine places, and it is what Spark's and Flink's Kafka sources use under
their own planning. A `read` that shares the existing loop, minus the rebalance, keeps one commit path for both
entry points. The alternative, `consume(assign = …)` as a flag on the existing function, keeps one name but brings
rebalance-only behaviour into a mode where it cannot happen. Recommended: a separate `read`.

## Stack

- [ ] **`spec-0120-partitions`** — `Kafka.partitions`, and the commit loop factored out of `Loop` so both share it.
      Done when: every existing `ConsumeTest` passes unchanged, and `partitions` lists a three-partition topic.
- [ ] **`spec-0120-read`** — `Kafka.read` with `From` and `Until`, and its `Decoder` overload.
      Done when: on embedded Kafka, reading three partitions with `Until.EndAtStart` gives every record produced
      before the reads opened, and none produced after. A read stopped and resumed from `From.Committed`, after
      `runCommitting`, sees each record at least once and loses none.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :lark-kafka:check && ./gradlew build
```

## Settled

Taken as recommended (2026-10-01): `until` has no default, this ships in 0.9.0, and `Kafka.partitions` asks a
short-lived consumer.

## Open questions

1. **Should `Until.EndAtStart` be the replay default for lark-job, or should the caller always choose?** Recommend
   that lark-kafka has no default, so `until` is a required argument. lark-job's `Dataset.kafka` picks a default
   for itself.
2. **Should this ship in 0.8.x or 0.9.0?** Recommend 0.9.0: it adds API, and 0.8.0 is already tagged.
3. **Should `Kafka.partitions` take an admin client or a consumer?** Recommend a short-lived consumer built from the
   same `properties`. That is one connection to configure, and `partitionsFor` is all it needs.

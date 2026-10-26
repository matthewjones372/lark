# 0159 — where a consumer starts reading, and what it hears about its partitions

## Problem

`Kafka.consume` takes named topics and starts where the group committed or `auto.offset.reset` says.
`Kafka.read` takes one partition and starts at `Committed`, `Earliest`, `Latest` or an `Offset`. Missing,
against zio-kafka's `Subscription` and `OffsetRetrieval`:

- **topics by pattern** (`Subscription.pattern`): a service that reads every `orders.*` topic;
- **many partitions assigned** (`Subscription.manual`): today a caller merges one `read` per partition;
- **offsets the caller keeps** (`OffsetRetrieval.Manual`): a group whose offsets live in the caller's own
  database, sought to on every assignment;
- **a start time** (`offsetsForTimes`): replay from 09:00;
- **rebalance hooks** (`RebalanceListener`): log, warm a cache, flush state when partitions come and go.

## Not doing

- Changing what `Topic` is, or how a record is committed.
- Static membership (`group.instance.id`): already a consumer property the caller sets.

## Shape

```kotlin
Kafka.consume(properties, Topics.matching(Regex("orders\\..*")), key, value)
Kafka.consume(properties, Topic("orders"), key, value, startAt = StartAt.Time(nineAm))
Kafka.consume(properties, Topic("orders"), key, value, startAt = StartAt.Own { partitions -> db.offsets(partitions) })
Kafka.consume(properties, Topic("orders"), key, value, onAssigned = { ps -> … }, onRevoked = { ps -> … })
Kafka.read(properties, Topic("orders"), partitions = listOf(0, 1, 2), key, value, from = From.Time(nineAm), until)
```

`From` gains `Time(Instant)`. `startAt` on `consume` applies at each assignment to a partition with nothing
committed, or always for `Own`. The hooks run on the polling thread, after surefold's own revoke handling.

## Why this shape

Parameters with defaults on the existing calls, rather than a `Subscription` type: every call today keeps
compiling, and each new choice reads where it is used. The alternative, one `Subscription` value as zio-kafka
has, is tidier with many options and costs every existing caller a change.

## Stack

- [x] **`spec-0159-pattern`** ([#700](https://github.com/matthewjones372/surefold/pull/700)): `Topics.matching`. Done when: a topic created mid-run is read.
- [x] **`spec-0159-many-partitions`** ([#701](https://github.com/matthewjones372/surefold/pull/701)): `Kafka.read` over a list of partitions, one consumer.
- [ ] **`spec-0159-start-at`**: `From.Time`, `StartAt.Time`, `StartAt.Own`.
- [ ] **`spec-0159-hooks`**: `onAssigned` and `onRevoked`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **Parameters or a `Subscription` value?** Recommended: parameters.
2. **Hooks: plain functions, or a `Raise` so a hook can fail the run?** Recommended: plain; a throw is a defect.
3. **`StartAt.Own` and commits:** a group that keeps its own offsets usually does not commit to Kafka. Should
   `runCommitting` then hand each handled offset to the caller instead? Recommended: yes, as a later entry.

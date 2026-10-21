# 0158 — a rebalance waits for what is in flight

## Problem

When a rebalance takes a partition away, `Kafka.consume` commits what was handled and forgets the rest
(`Consume.kt`, `onPartitionsRevoked`; spec 0154 entry 3 drops marks made after it). Records already in the
stream, more of them under `mapParRecord` and on Pekko, are then read again by the partition's new owner: every
deploy and every scale-out re-processes up to a window's worth of records per partition.

zio-kafka's `rebalanceSafeCommits` holds the rebalance, inside the revoke callback, until the records already
handed out for that partition are committed or `maxRebalanceDuration` passes.

`stop()` already drains (`docs/stream.md`: what the source sent still reaches the sink, and lark-kafka commits it
on close), so shutdown itself is not the gap; a rebalance is.

## Not doing

- Changing what a crash re-delivers: at-least-once stays the contract.
- Cooperative-sticky assignment: the caller's `partition.assignment.strategy` already chooses it, and it narrows
  which partitions move rather than what happens to the records of one that does.

## Shape

```kotlin
// lark-kafka: off by default, as zio-kafka's.
Kafka.consume(properties, topic, key, value, rebalance = Rebalance.waitFor(30.seconds))
```

Inside `onPartitionsRevoked`, on the polling thread: the records handed out for the revoked partitions are
known (the `Commits` epoch per partition); wait until each is handled or the bound passes, commit, then let the
rebalance go on. The stream keeps running meanwhile; only the poll is held.

## Why this shape

The revoke callback is the one place Kafka lets a consumer hold a rebalance. Waiting there is what zio-kafka
does; the alternative, committing nothing and relying on idempotent handlers, is today's behaviour. Off by
default because a stuck handler then holds the whole group's rebalance up to the bound.

Depends on 0157: with the poll held inside the callback, nothing else may need it.

## Stack

- [ ] **`spec-0158-revoke-test`**: two consumers in a group, one joining mid-stream, counting re-deliveries.
      Done when: it measures today's duplicates.
- [ ] **`spec-0158-wait-on-revoke`**: `Rebalance.waitFor`, on `Kafka.consume`. Done when: with it on, the test
      sees no duplicates, and with it off, the same count as today.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Off by default?** Recommended: yes, as zio-kafka's.
2. **A parameter, or a consumer property?** Recommended: a parameter; it is lark's behaviour, not the client's.
3. **What the bound's expiry does:** commit what was handled and let the rest be re-delivered, as today.
   Recommended: yes.

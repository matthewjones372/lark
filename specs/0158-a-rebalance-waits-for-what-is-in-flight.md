# 0158 — a rebalance waits for what is in flight

## Problem

When a rebalance takes a partition away, `Kafka.consume` commits what was handled and forgets the rest
(`Consume.kt`, `onPartitionsRevoked`; spec 0154 entry 3 drops marks made after it). Records already in the
stream, more of them under `mapParRecord` and on Pekko, are then read again by the partition's new owner: every
deploy and every scale-out re-processes up to a window's worth of records per partition.

zio-kafka's `rebalanceSafeCommits` holds the rebalance, inside the revoke callback, until the records already
handed out for that partition are committed or `maxRebalanceDuration` passes.

`stop()` already drains (`docs/stream.md`: what the source sent still reaches the sink, and surefold-kafka commits it
on close), so shutdown itself is not the gap; a rebalance is.

## Not doing

- Changing what a crash re-delivers: at-least-once stays the contract.
- Cooperative-sticky assignment: the caller's `partition.assignment.strategy` already chooses it, and it narrows
  which partitions move rather than what happens to the records of one that does.

## Shape

```kotlin
// surefold-kafka: off by default, as zio-kafka's.
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

- [x] **`spec-0158-revoke-test`** ([#692](https://github.com/matthewjones372/surefold/pull/692)): two consumers in a group, one joining mid-stream, counting re-deliveries.
      Done when: it measures today's duplicates.
- [ ] **`spec-0158-wait-on-revoke`**: `Rebalance.waitFor`, on `Kafka.consume`. Done when: with it on, the test
      sees no duplicates, and with it off, the same count as today.

Found while writing `spec-0158-revoke-test`. On Pekko, a member whose stream has every record in hand rejoins a
rebalance only through its keeper. The keeper makes a zero-timeout poll every half `max.poll.interval.ms`. With a
2s interval, those polls do not finish the rejoin before the group drops the member. Its close then dies on the
revoke's commit with `CommitFailedException`. At 10s the polls do finish it, and the test runs at 10s. Holding the
rebalance inside the revoke callback (entry 2) runs inside that same keeper poll on Pekko.

Found while building `spec-0158-wait-on-revoke`:

- **The duplicates are all six records, not three.** The default assignors make an eager rebalance, which takes
  every partition from a member, including the ones it is given back. So the records the first member had in hand
  on the partition it kept are dropped too, and read again once it leaves. Entry 1's test missed this because the
  second member stopped after three records. The test now follows both members to the end and counts six.
- **An interrupt can arrive mid-wait.** A take that ends a Forks run interrupts the polling fork, often just as the
  last record the revoke waits for is handled. The revoke clears the interrupt, finishes its wait and its commit,
  then sets the interrupt again, as `Commits.close` does. Before that fix, about four runs in ten re-read two to
  five records.
- **The wait is woken by marks, not by sleeping.** `Thread.sleep` is forbidden in library code. Each mark releases
  a permit if none is waiting, and the revoke waits on that permit.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **Off by default?** Recommended: yes, as zio-kafka's.
2. **A parameter, or a consumer property?** Recommended: a parameter; it is surefold's behaviour, not the client's.
3. **What the bound's expiry does:** commit what was handled and let the rest be re-delivered, as today.
   Recommended: yes.

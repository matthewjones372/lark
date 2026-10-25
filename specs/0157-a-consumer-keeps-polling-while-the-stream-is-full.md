# 0157 — a consumer keeps polling while the stream behind it is full

## Problem

`Kafka.consume` polls only when the stream pulls (`Consume.kt`, `Loop.next`). A handler slower than
`max.poll.interval.ms` (5 minutes by default) for one poll's worth of records stops the polling, the broker
removes the consumer from its group, every partition is rebalanced, and the records it was working on are read
again by someone else. Nothing in lark-kafka mentions
`max.poll.interval.ms`, pauses a partition, or polls without a pull.

zio-kafka polls on its own loop and pauses the partitions whose buffer is full (`FetchStrategy`,
`withPartitionPreFetchBufferLimit`), so a slow handler never stops the poll.

## Not doing

- A buffer per partition, or per-partition backpressure (`FetchStrategy`'s other half): 0156 decides how
  partitions share the work.
- Changing how many records one poll returns (`max.poll.records` stays the caller's property).
- The Pekko connector, `Kafka.subscribe`: its own consumer actor already polls on its own.

## Shape

No API change. A run's consumer gets a poller of its own that owns the `KafkaConsumer`, as now only one thread
touches it:

- it polls whenever the stream has taken what the last poll returned, as now;
- when the stream has not taken it within `poll interval / 2`, it **pauses** every assigned partition and polls
  anyway, which returns nothing and keeps the member alive; it **resumes** them once the stream catches up;
- commits, the rebalance listener and the close stay on that thread.

## Why this shape

Two ways:

- **(a) Pause everything and keep polling** while the stream is behind. The member stays alive, nothing is
  read ahead, and the stream's order is the poll's order.
- **(b) Keep fetching into a bounded buffer** (zio-kafka's prefetch), pausing only when the buffer is full.
  Faster on a burst, but a buffer to size and to drop on revoke.

Recommended: (a) first. It closes the correctness gap; (b) is a throughput change to measure on its own.

## Stack

- [x] **`spec-0157-slow-handler-test`** ([#450](https://github.com/matthewjones372/lark/pull/450)): a test whose handler takes longer than a short `max.poll.interval.ms`,
      failing today with a rebalance. Done when: it fails on `main` for the stated reason.
- [ ] **`spec-0157-poller`**: the poller, pause and resume, on `Kafka.consume`. Grouped `Kafka.read` needs none:
      it assigns its partition rather than subscribing, so it is no member of the group, and no poll interval drops
      it; a `ReadSuite` test with a handler slower than `max.poll.interval.ms` shows it keeps its place and commits.
      Done when: that test passes on Forks and Pekko, and the comparison benchmarks do not get slower.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 10 -i 10 -r 1 -w 1 -t 1 -f 5 .*ComparisonBenchmarks.*"
```

## Open questions

1. **(a) or (b)?** Recommended: (a). **Answered:** (a).
2. **A thread per consumer**, where today the stream's own thread polls. Recommended: a virtual thread, started
   with the run and stopped with it, as `mapPar`'s feeding fork is. **Answered:** yes.
3. **Pause at half the poll interval?** Recommended: yes, read from the consumer's own config. **Answered:** yes.

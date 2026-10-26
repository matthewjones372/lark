# 0156 — records handled in parallel, in order only within their partition

## Problem

`mapParRecord(n)` keeps the stream's order: a record's result waits for every record read before it, from every
partition. Kafka promises order only within a partition, so one slow call holds back records of the other five
partitions that have nothing to wait for, and with them the window: a body that is done cannot give its slot
back until the head is. With a uniform handler that costs little (spec 0154: `surefoldMapPar` 136 ms against the
hand-written fan-out loop's 142 ms at 1 ms a record); with a skewed one, every partition runs at the slow one's
pace.

## Not doing

- A stream per partition (zio-kafka's shape): a new shape every operator would have to learn.
- Unordered parallelism. An offset commits only once every record before it on its partition is done, so
  handling a partition out of order would hold its commits back, not speed them up.
- Changing `mapPar` or `mapParRecord`. Their order is what callers rely on.

## Shape

```kotlin
// surefold-stream: up to `parallelism` bodies at once; each result passes on once every earlier element with the
// same key has. Elements with different keys pass each other.
fun <E, A : Any, B : Any> Stream<E, A>.mapParKeyed(parallelism: Int, key: (A) -> Any, f: Raise<E>.(A) -> B): Stream<E, B>

// surefold-kafka: keyed by topic and partition.
fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParPartitioned(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, Committed<B>>
```

`Node.MapPar` gains `key: ((Any) -> Any)?`, null for `mapPar`, so no other node and no backend's exhaustive
`when` changes. Forks and Pekko release results as they complete, each chained behind the previous one with its
key: Pekko through its own `mapAsyncUnordered`. Actors and TestStreams keep releasing in full order, which keeps
every key's order too: correct, and no faster.

## Why this shape

Two ways:

- **(a) A keyed `mapPar` in surefold-stream**, which surefold-kafka keys by partition. It is general (a keyed stream of
  anything), and only the two backends that hand out results across threads need new code.
- **(b) surefold-kafka only.** surefold-stream has no unordered operator to build it on; it would need one anyway.

Recommended and answered: (a).

## Stack

- [x] **`spec-0156-skew-row`** ([#457](https://github.com/matthewjones372/lark/pull/457)): a `ProcessingBenchmarks` row where one partition's handler is 10× slower,
      measured against today's code. Done when: the row is in the baseline.
- [x] **`spec-0156-keyed-forks`** ([#452](https://github.com/matthewjones372/lark/pull/452)): `mapParKeyed`, the `key` field, Forks, and parity tests. Done when: on Forks a
      slow element on one key does not delay another key's results, and every key's order holds on every backend.
- [x] **`spec-0156-keyed-pekko`** ([#453](https://github.com/matthewjones372/lark/pull/453)): the same on Pekko. Done when: the parity test's no-delay case runs on Pekko too.
- [x] **`spec-0156-kafka`** ([#458](https://github.com/matthewjones372/lark/pull/458)): `mapParPartitioned`, its docs, and a skewed row for it. Done when: on the skewed row
      surefold beats `javaParallelBatch` by more than on the uniform one.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 3 -i 5 -r 1 -w 1 -t 1 -f 2 .*ProcessingBenchmarks.*"
```

## Open questions

1. **Measure first?** **Answered:** yes; the skew row lands first and alone.
2. **(a) or (b)?** **Answered:** (a).
3. **A per-key cap on bodies in flight?** A hot key could fill the window. **Deferred:** measure on the skew row
   first; a cap is a parameter that can come later.

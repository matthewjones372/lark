# 0120 — A job across the cluster

## Problem

lark can run a stream on one node and place actors across many, but it cannot split one batch computation over
the cluster. Someone with ten million rows to count by key reaches for Spark. That means a second cluster, a second
deploy, closures shipped through Java serialisation (a captured logger fails only at run time), and failures as
untyped exceptions in an executor's log. Spark also writes every shuffle to disk before reading it back, even when
the data fits in memory.

Better than Spark here means three checkable things, not speed: nothing a job does is serialised except the
elements it declares a codec for. A job's declared failure comes back as `Exit.Failed(e)`. The same job value runs
in a unit test on `Forks` and on a cluster without a change.

## Not doing

- **SQL, a query optimiser, DataFrames, notebooks.** A job is a typed description, written in Kotlin.
- **Spilling a shuffle to disk.** This spec keeps the shuffle in memory, bounded by credits. Spilling is its own spec.
- **File formats** (Parquet, CSV readers). A source is a stream that the caller builds per partition.
- **Surviving the loss of the node that submitted the job.** The job fails with `Died`, and the caller resubmits.
- **A performance claim against Spark.** That needs a benchmark, which gets its own spec.

## Shape

```kotlin
val wordCount = Job("word-count", partitions = 16) { p -> lines(p) }   // built on the node running partition p
    .mapConcat { line -> line.split(' ') }
    .keyBy(Codecs.string) { word -> word }                            // the key crosses the wire
    .reduce(Codecs.long, { 1L }) { a, b -> a + b }                    // and so does the partial count
    .collect()                                                        // Job<Nothing, Map<String, Long>>

val jobs = cluster.jobs(wordCount)        // on every node, as `sharding` is
val exit = jobs.run(wordCount, within = 5.minutes)                    // on any node: Exit<E, Map<String, Long>>

wordCount.runLocally(Forks()) shouldBe Exit.Done(expected)          // the same value, in a test
```

- **A job is a value that every node builds.** No lambda crosses the wire. The submitting node sends
  `(job, stage, partition)` and the node that runs it already holds the code. This is how `sharding` and `spread`
  already work.
- **A stage is a lark `Stream`.** It is the source, then the narrow operators (`map`, `filter`, `mapOrFail`,
  `mapConcat`) fused as they are today, up to the next `keyBy`. Each partition's stage runs on that node's `Actors`
  backend.
- **The shuffle is pipelined.** A map task combines locally per key, then sends `(key, partial)` batches to the
  reducer for `hash(key) mod partitions`. It sends only while it holds credit from that reducer, so a slow reducer
  slows the mappers instead of filling a mailbox (spec 0101). Batches go on `Lane.Data`.
- **Placement** reuses `Placement.spread`: the tasks of a stage are spread evenly over the `Up` members (or a
  `role`'s members).
- **Failure.** A declared failure in any task ends the job with `Exit.Failed(e)`, and the other tasks are stopped.
  A defect, or a member lost mid-task, reruns that task elsewhere, up to `retries`. A lost reducer reruns its
  stage's mappers, because nothing was written down.

## Why this shape

Shipping code as named values instead of closures gives up Spark's ad-hoc `spark-shell` jobs. In exchange, a job
cannot fail because something it captured will not serialise, and the wire carries only typed elements through
codecs it already has. A pipelined shuffle avoids Spark's disk round trip and is simpler to build on actors that
already apply backpressure. The cost is that a lost reducer reruns a whole stage. The alternative, materialised
map outputs as in Spark, recovers more cheaply but needs local storage. It is recommended as the follow-up spec,
not as the first one.

## Stack

- [ ] **`spec-0120-job`** — new `lark-job` module: `Job`, the narrow operators, `collect`, `runLocally` on one
      node, and `NoOtherDependenciesTest`.
      Done when: 16 partitions on `Forks` give the same `collect` as one sequential stream, and a `fail(e)` in
      partition 7 comes back as `Exit.Failed(e)`.
- [ ] **`spec-0120-shuffle`** — `keyBy`, `reduce`, `groupBy`, with a local combine and an in-node shuffle under
      credits.
      Done when: word count over 1,000,000 words matches a sequential `groupingBy`, and no mailbox ever holds more
      than its credit.
- [ ] **`spec-0120-cluster`** — `cluster.jobs`, task placement by `spread`, and the shuffle across nodes through
      codecs.
      Done when: the same word count on 3 nodes in one JVM matches, with tasks on every node.
- [ ] **`spec-0120-retry`** — rerunning a task after a defect or a lost member, plus `retries`.
      Done when: one node downed mid-job still gives the exact answer, and a declared failure is never retried.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :lark-job:check :lark-cluster:check && ./gradlew build
```

## Open questions

1. **A new module, or part of `lark-cluster`?** Recommend a new `lark-job` module, depending on
   `lark-stream-actors` and `lark-cluster` only. That keeps `runLocally` usable without a cluster on the classpath
   at test time.
2. **Pipelined shuffle, or materialised map outputs?** Recommend pipelined for this spec (see *Why this shape*).
3. **Registering jobs on every node goes against "no registry".** Recommend accepting it, the same exception
   `sharding` already makes. The other option is a job registered by `ServiceKey` through the receptionist, which
   has the same cost under another name.
4. **Which result shapes come first?** Recommend `collect` (to a `Map` or `List`) and `fold`. Writing results to a
   sink per partition (a journal or a Kafka topic) waits for a later spec.

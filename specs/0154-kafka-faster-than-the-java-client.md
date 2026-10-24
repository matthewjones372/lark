# 0154 — lark-kafka measured against the Java client, and the three costs it adds

## Problem

zio-kafka's comparison benchmarks (50k × 512 B records, 6 partitions, `max.poll.records=1000`, broker in
the JVM, a handler that counts) ported to lark, JMH `-wi 10 -i 10 -f 5`, 4 vCPU:

| row | ms/op |
|---|---|
| Java `kafkaClients`, one consumer per trial, no commits | 557 ± 3 |
| Java, a fresh consumer per run | 571 ± 4 |
| Java, fresh, `commitSync()` before each poll (what lark does) | 646 ± 7 |
| `Kafka.consume(…).runCommitting()` on Forks | 657 ± 5 |
| the same on Pekko | 854 ± 9 |

lark's own streaming costs ~0.2 ms a poll over its hand-written twin. It loses on two choices, and every
run pays a third cost that Kafka adds:

- **`commitSync` before every poll: 86 ms of 135 ms polling** (`commitAsync`: 49 ms, plus 22 ms in the
  final `commitSync`, which waits for those still in flight).
- **Pekko's blocking source: +197 ms.** `unfoldResource` reads on the blocking-IO dispatcher and hands
  each record across that async boundary on its own.
- **`close()`: ~500 ms of every run, Java's included.** Its fetcher phase sends a fetch-session close that
  queues behind the prefetch the broker holds for `fetch.max.wait.ms`. `close(0)` takes 3 ms and the
  group still sees the leave on this broker, but that is a local broker.

## Not doing

- Virtual-thread wake-up cost (+70 µs per blocking call, measured): the JDK's, not lark's.
- Per-partition substreams (zio-kafka's shape). A new API, its own spec.
- Per-record allocations (~170 B over the Java loop). Measured, too small to lead with.

## Shape

No public signature changes for 1–3. Entry 4 adds one overload:

```kotlin
fun <R : Any, A : Any> Stream.Companion.blocking(
    open: () -> R,
    next: (R) -> A?,
    ready: (R) -> Boolean,   // true: the next `next` returns without blocking
    wake: (R) -> Unit,
    close: (R) -> Unit,
): Stream<Nothing, A>
```

Pekko reads `next` into one batch while `ready` is true and crosses the boundary once per batch; Forks
ignores `ready`. lark-kafka answers it with `polled.hasNext()`.

## Why this shape

Batching inside lark-kafka (one element per poll, then `mapConcat`) needs no new API, but it puts up to
1,000 records past the revoke filter at once. `ready` keeps the filter per record and helps every
blocking source on Pekko. **Recommended: `ready`.** Either way, reading further ahead makes a guard
necessary (entry 3).

## Stack

- [ ] **`spec-0154-benchmarks`**: `lark-kafka-benchmarks`, the ported comparison, the handler-latency
      sweep, timelines, a README with the baseline. Done when: `:lark-kafka-benchmarks:jmh` reproduces
      the table above.
- [ ] **`spec-0154-commit-async`**: `commitAsync` before each poll; `commitSync` on revoke and close; a
      non-retriable async failure is thrown on the next commit, on the polling thread. Done when: a test
      shows the pre-poll commit does not wait, and `ConsumeTest`, `CommitsTest`, `ReadTest` and
      `TransactTest` pass unchanged.
- [ ] **`spec-0154-revoked-handles`**: a record handled after its partition was revoked commits nothing.
      Done when: a test that handles a record after its revoke sees no offset for that partition.
- [ ] **`spec-0154-blocking-ready`**: the overload above; Pekko batches; lark-kafka answers it. Done
      when: `larkPekko` is within 10% of `larkForks`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 10 -i 10 -r 1 -w 1 -t 1 -f 5 .*ComparisonBenchmarks.*"
```

## Open questions

1. **The close tail.** (a) Leave it to Kafka. (b) A `closeTimeout` parameter, default unchanged.
   (c) Default it to ~100 ms: offsets are already committed, but a leave slower than that stalls the
   group for `session.timeout.ms`. Recommended: (b), as a fifth entry once this lands.
   **Deferred** to that entry.
2. **Benchmark sources against the 200-line cap.** The module is ~680 lines. Recommended: count them
   like tests (`benchmarkModules` already excludes them from coverage and publishing).
   **Answered:** counted like tests.
3. **Async-commit failures.** Retriable ones are retried by the next commit. Recommended: anything else
   is a defect, as a `commitSync` throw is today. **Answered:** a defect.

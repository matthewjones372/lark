# lark-kafka-benchmarks

lark-kafka against the plain Java client, measured by JMH. The `ComparisonBenchmarks` are zio-kafka's
[comparison benchmarks](https://github.com/zio/zio-kafka/tree/master/zio-kafka-bench), ported, as used in
Erik van Oosten's [“Zio-kafka, faster than java-kafka”](https://day-to-day-stuff.blogspot.com/2024/12/zio-kafka-faster-than-java-kafka.html).
Spec [0154](../../specs/0154-kafka-faster-than-the-java-client.md) reports against this baseline.

```bash
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 10 -i 10 -r 1 -w 1 -t 1 -f 5 .*ComparisonBenchmarks.*"   # ~20 minutes
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 3 -i 5 -r 1 -w 1 -t 1 -f 2 .*ProcessingBenchmarks.*"
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 3 -i 5 -r 1 -w 1 -t 1 -f 2 .*PublishBenchmarks.*"
./gradlew :lark-kafka-benchmarks:timelines     # one stamped run of each consumer, to build/timelines.json
./gradlew :lark-kafka-benchmarks:experiments   # a fresh consumer's join, polls, commits and close, timed
```

Each fork starts an embedded broker in its own JVM, as zio-kafka's do, and writes 50k records of ~512 bytes
over 6 partitions, plus 5k to a second topic for the handler sweep. Consumers use zio-kafka's test settings:
`max.poll.records=1000`, a 100 ms poll timeout, `auto.offset.reset=earliest`.

## Baseline, 2026-10-21

JDK 25.0.4, 4 vCPU / 16 GB (a shared cloud container), kafka-clients and embedded-kafka 4.3. Raw results:
[`baseline/`](baseline).

| `ComparisonBenchmarks` row | What it does | ms/op |
|---|---|---|
| `kafkaClients` | zio-kafka's: one consumer per trial, subscribe, poll until 50k, unsubscribe | 557 ± 3 |
| `manualKafkaClients` | zio-kafka's: the same with the six partitions assigned | 553 ± 3 |
| `kafkaClientsFreshConsumer` | a new consumer and group per run, closed at the end | 571 ± 4 |
| `kafkaClientsCommitting` | the same, `commitSync()` before each poll and at the end | 646 ± 7 |
| `larkForks` | `Kafka.consume(…).take(50k).runCommitting()` on Forks | 657 ± 5 |
| `larkForksMetered` | `larkForks` with its meters recorded to an in-memory backend (spec 0163) | — |
| `manualLarkForks` | six `Kafka.read`s to `EndAtStart`, merged, nothing committed | 571 ± 3 |
| `larkPekko` | `larkForks`' description on Pekko Streams | 854 ± 9 |
| `larkPekkoConnector` | `Kafka.subscribe` from lark-kafka-pekko, with Pekko's committer | 716 ± 11 |

`larkForksMetered` came after the baseline. On the stack through spec 0163, three forks of both rows in one run gave
141 ± 15 ms/op metered against 140 ± 17 for `larkForks`: the meters cost nothing measurable.

`ProcessingBenchmarks`: 5k records, each handled by a blocking call of the given length, committed as they go.

| Row | 0 µs | 100 µs | 1,000 µs |
|---|---|---|---|
| `javaSerial`: the poll loop | 31 ± 6 | 896 ± 14 | 5,647 ± 46 |
| `larkSerial`: `mapRecord` | 37 ± 5 | 1,023 ± 22 | 6,044 ± 75 |
| `javaParallelBatch`: each poll fanned out to ≤64 virtual threads, then committed | 37 ± 8 | 55 ± 5 | 142 ± 7 |
| `larkMapPar`: `mapParRecord(64)` | 50 ± 4 | 60 ± 20 | 136 ± 5 |

`SkewBenchmarks` is that parallel pair at 1,000 µs, with partition 0's records handled `skew` times slower, as when
one tenant's writes are slow (spec [0156](../../specs/0156-ordered-per-partition.md)). With the default `fetch`, each
partition comes back whole, so a slow partition is one run of records; `interleaved` fetches 16 KB a partition,
so each poll carries a little of every partition, as a topic with steady traffic does. Measured before
`mapParKeyed`, `-f 2 -wi 3 -i 5` ([`2026-10-21-jdk25-skew.json`](baseline/2026-10-21-jdk25-skew.json)), in ms/op:

| Row | whole, ×1 | whole, ×10 | interleaved, ×1 | interleaved, ×10 |
|---|---|---|---|---|
| `javaParallelBatch` | 157 ± 19 | 291 ± 17 | 740 ± 22 | 1,027 ± 35 |
| `larkMapPar` | 159 ± 9 | 297 ± 36 | 170 ± 28 | 409 ± 17 |

Interleaved, the hand-written loop waits for each small poll's slowest record before it polls again; lark keeps
polling, but every result still waits behind the slow partition's.

With `mapParPartitioned`, a result waits only behind its own partition's. Measured in one run with the rows above
([`2026-10-21-jdk25-skew-partitioned.json`](baseline/2026-10-21-jdk25-skew-partitioned.json)):

| Row | whole, ×1 | whole, ×10 | interleaved, ×1 | interleaved, ×10 |
|---|---|---|---|---|
| `javaParallelBatch` | 154 ± 9 | 287 ± 6 | 736 ± 22 | 1,007 ± 24 |
| `larkMapPar` | 162 ± 11 | 287 ± 14 | 165 ± 9 | 409 ± 31 |
| `larkMapParPartitioned` | 156 ± 9 | 288 ± 13 | 157 ± 19 | 279 ± 11 |

Interleaved and skewed, it takes a third off `larkMapPar` and runs 3.6× as fast as the hand-written loop. Fetched
whole, a slow partition is one run of records with nothing behind it to let through, and every row is the same.

`PublishBenchmarks` sends 20k records of 512 bytes to a topic of its own, at most 1,000 waiting on the broker at
once: `publishTo`, which is `mapAsync` over the producer's futures, against the Java producer bounded by a
`Semaphore` released in its callback. Added after the baseline, and measured on the same machine before and after
spec 0172 (which rebuilt Forks' `mapAsync` and `mapPar` windows), two rounds each, in ms/op:

| Row | before 0172 | after 0172 |
|---|---|---|
| `javaProducer` | 1,019 / 1,026 | 1,103 / 936 |
| `larkPublishTo` | 942 / 1,119 | 1,066 / 954 |

Both run at the broker's pace, about 20k records a second here, and lark is level with the Java producer. Spec
0172 moved none of this module's rows outside the noise between rounds, which the Java rows show is ±10–15% on a
shared machine: a poll, a commit or the broker, not the hand-off inside the stream, is what a Kafka pipeline waits on.

What the rows say:

- **About 500 ms of every 50k run is `close()`**, the Java loop's included: the fetch-session close queues
  behind the prefetch the broker holds for `fetch.max.wait.ms`. `experiments` shows the polls themselves take
  ~50 ms.
- **lark's streaming adds about 0.2 ms a poll** over the hand-written loop that commits the same way.
- **Committing synchronously before each poll costs about 85 ms** of that run.
- **Pekko's blocking source adds about 200 ms** over Forks for the same description.
- **`larkSerial` loses to `javaSerial` by ~70 µs a call:** the body runs on a virtual thread, whose wake-up
  from a blocking call costs that much more than a platform thread's. `experiments` measures it directly.
- **Once a handler waits on I/O, `mapParRecord` wins**: it keeps polling while calls are in flight.

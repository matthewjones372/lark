# 0163 — surefold-kafka's own meters

## Problem

surefold-kafka records nothing. A service running `Kafka.consume(…).runCommitting()` cannot see how far behind it
is, how long a record waits between being written and being handled, how often it commits or rebalances, or how
often its producer fails, without wiring the Kafka client's metrics up itself, and surefold hides the client.

Every other surefold module records under `surefold.*` through core's `counter`, `gauge`, `histogram` and `timed`
(`docs/observability.md`, *Surefold's own metrics*): installing `surefold-micrometer` exports them, and with no backend
bound they cost nothing. zio-kafka exposes the client's metrics and its own run-loop metrics
(`withMetricsLabels`, `withRunloopMetricsSchedule`).

## Not doing

- A new dependency: surefold-kafka records through `surefold`'s API; Micrometer stays `surefold-micrometer`'s.
- Traces: `surefold-otel` already carries a span across a record (`TraceCarrier`); spans per poll would be their own
  spec.
- Every one of the client's ~100 metrics. A curated set below; the rest stay readable through 0160's `metrics()`.

## Shape

Tagged `topic` and `partition` where the meter is about a partition, and `group` and `client` on every meter;
`metricTagged` around a run adds the caller's own. Partition counts are bounded, so the tags are.

| Meter | Kind | Read from |
|---|---|---|
| `surefold.kafka.consumer.records`, `.bytes` | counter | each poll |
| `surefold.kafka.consumer.lag` | gauge | the client's `records-lag`, per partition, after each poll |
| `surefold.kafka.consumer.latency` | histogram, s | now − the record's timestamp, when it is handled |
| `surefold.kafka.consumer.in.flight` | gauge | records handed to the stream and not yet handled |
| `surefold.kafka.consumer.poll`, `.commit`, `.close` | timer | around each |
| `surefold.kafka.consumer.commit.failures`, `.rebalances` | counter | the commit callback, the rebalance listener |
| `surefold.kafka.consumer.assigned`, `.paused` | gauge | the assignment, 0157's pause |
| `surefold.kafka.producer.records`, `.bytes`, `.failures` | counter | `publish` |
| `surefold.kafka.producer.ack` | histogram, s | send to acknowledgement |
| `surefold.kafka.dead.letters` | counter | `deadLetters` |
| `surefold.kafka.transactions`, `.aborts` | counter, timer | `transacted` |

## Why this shape

Through surefold's API rather than Micrometer's `KafkaClientMetrics` binder: the binder needs the client instance,
which surefold keeps to one thread, and it would make surefold-kafka depend on Micrometer. Lag from the client's own
`records-lag` rather than asking the broker for end offsets: it is already computed on every fetch, so reading it
costs no request. Latency from the record's timestamp, because it is the number an alert is written on: how stale
the work is, not how fast one call is.

## Stack

- [x] **`spec-0163-consumer`** ([#451](https://github.com/matthewjones372/lark/pull/451)): records, bytes, poll, commit, close, rebalances, assigned, in flight.
      Done when: a test reads each back through `readings(name)` after a run.
- [x] **`spec-0163-lag-latency`** ([#454](https://github.com/matthewjones372/lark/pull/454)): `lag` and `latency`. Done when: lag reads N with N records unread, then 0.
- [x] **`spec-0163-producer`** ([#455](https://github.com/matthewjones372/lark/pull/455)): producer, dead letters, transactions.
- [x] **`spec-0163-docs`** ([#456](https://github.com/matthewjones372/lark/pull/456)): the table in `docs/observability.md` and `docs/kafka.md`, and a benchmark row showing
      that recording costs nothing measurable with no backend bound.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 10 -i 10 -r 1 -w 1 -t 1 -f 5 .*ComparisonBenchmarks.*"
```

## Open questions

1. **Per-partition tags by default?** Recommended: yes; partitions are few. A topic with thousands would want
   them off: a switch, later, if anyone has one. **Answered:** yes.
2. **Latency from `CreateTime` or `LogAppendTime`?** Recommended: whichever the topic records; the record says. **Answered:** yes.
3. **Ahead of 0160?** Recommended: yes. Meters are what an on-call engineer reads; the questions are for code. **Answered:** yes.

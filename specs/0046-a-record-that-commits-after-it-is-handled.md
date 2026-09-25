# 0046 — A record that commits after it is handled

## Problem

A service that reads a Kafka topic with lark today builds its source from Pekko
Connectors Kafka. It passes that source to `Stream.from` and keeps the
`CommittableOffset` beside every element by hand, as a `Pair`, through every
`map` and `mapPar`. Then it has to remember to end the stream on
`Committer.sink`. If it forgets, or commits before the work, the compiler says
nothing. The service loses records on a crash, or reads them all again on
every restart. `Stream.from` also drops the `Consumer.Control`, so a shutdown
cannot drain: in-flight work is cut off, and none of it is committed.

## Not doing

- **No decoding.** Keys and values come through Kafka's own `Deserializer`.
  Turning a record that fails to decode into a value, not a defect, is 0047.
- **No producing.** `publishTo` and a blocking `send` are 0048.
- **No exactly-once or transactions.** Nobody has asked for them.
- **No broker-free fake.** A test runs against a real broker until 0048 adds
  the fake.
- **No lark-app node type.** `singleOf(start, Running::close)` already does the
  job, as in 0044.
- **No manual partition assignment or seek.** A subscription is only a group
  and topics.

## Shape

```kotlin
val placed: Running<ShopError, Done> =
    Kafka.subscribe(consumer, Topic("orders"))           // Stream<Nothing, Committed<ConsumerRecord<OrderId, Order>>>
        .mapRecord { it.value() }                        // Stream<Nothing, Committed<Order>>
        .mapParRecordOrFail(4) { order -> shop.place(order).bind() }  // Stream<ShopError, Committed<Receipt>>
        .runCommitting(committer)                         // Run<ShopError, Done>
        .start(system)

placed.close()   // stop fetching, finish what is in flight, commit it, then wait for the exit
```

- A new module, `lark-kafka`. It depends on `lark-stream` and
  `pekko-connectors-kafka`, and on nothing else. Its own
  `NoOtherDependenciesTest` holds that.
- `Kafka.subscribe(settings: ConsumerSettings<K, V>, vararg topics: Topic):
  Stream<Nothing, Committed<ConsumerRecord<K, V>>>`.
- `Committed<out A>` is an element and the offset that comes with it. The
  offset is not in its public API.
- On `Stream<E, Committed<A>>`, these operators work on `A` and keep the
  offset: `mapRecord`, `mapRecordOrFail`, `mapParRecord`,
  `mapParRecordOrFail`, `filterRecord` and `mapConcatRecord`. A filtered
  record is committed with the next record on its partition that is not
  filtered.
- `mapConcatRecord` gives the record's offset to the last element it expands to.
  The elements before it carry none, so the record commits only once all of
  them have reached the committer. An expansion to nothing commits as a
  filtered record does. This relies on the element operators keeping input
  order, which `mapPar` already does.
- `Stream<E, Committed<*>>.runCommitting(settings: CommitterSettings):
  Run<E, Done>` is the only way to run a stream of `Committed` elements.
  `runCollect`, `runFold` and `runWith` over `Committed` do not compile. They
  are overloads deprecated at `ERROR` in lark-stream's package, so importing
  lark-stream's `runCollect` imports them too.
- On a `Running` from `runCommitting`, `stop()` is the consumer's
  `drainAndShutdown`. The consumer stops fetching, the elements already in
  flight finish and are committed, and the exit completes after that.
- A body runs inside `logAnnotated("kafka.topic", "kafka.partition",
  "kafka.offset")`, so every line it writes names its record.
- `restartOnDefect` over a subscription starts again from the last commit.

## Why this shape

Offsets are carried in a `Committed<A>` element, not in a second stream type,
so the same `Stream` type and every other operator still apply. A
`KafkaStream<E, A>` could hide the offset completely, but it would need every
operator written a second time. Commit is the only terminal operation, which
turns the two usual Kafka bugs into compile errors. The first is forgetting to
commit. The second is committing before the work, because the work has to sit
before the terminal operation. Draining on `stop()` rather than using 0044's
kill switch matters: the kill switch drops what is in flight. That is still
at-least-once, but every shutdown sends those records again.

## Stack

- [ ] **`spec-0046-subscribe`**: the `lark-kafka` module, `Committed`,
      `Kafka.subscribe`, `runCommitting` over `Committer.sink`, and the dependency test.
      Done when: against a broker in the test JVM, the records a run handles
      are committed, and a second run in the same group sees none of them again.
- [ ] **`spec-0046-offset-keeping-operators`**: `mapRecord`,
      `mapRecordOrFail`, `mapParRecord`, `mapParRecordOrFail`, `filterRecord`
      and `mapConcatRecord` over `Committed`, and the log annotations.
      Done when: a compile test holds that `runCollect` over `Committed` does
      not compile; a body that raises on record 3 of 5 leaves offset 2
      committed; and a record expanded to three elements, whose third raises,
      is not committed.
- [ ] **`spec-0046-draining-stop`**: `stop()` drains through `Consumer.Control`.
      Done when: a `stop()` while a slow `mapPar` body is running still commits
      that body's record, and `close()` returns only after the commit.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How does `lark-kafka` reach inside a `Stream`?** `Stream`'s source and
   `Run`'s graph are `internal`, and draining needs the materialised
   `Control`. Recommend a narrow public seam in `lark-stream` behind an
   opt-in annotation. Moving Kafka into `lark-stream` would break that
   module's dependency rule.
2. **Do the `Committed` overloads resolve without annotations?** Answered:
   no. With both packages imported, every overload was ambiguous, because
   the lambda's parameter type differs between the two and so neither is more
   specific. The operators carry a `Record` suffix instead, and a compile test
   pins them beside lark-stream's own.
3. **Does `grouped` belong here?** When many records become one element, the
   batch has to carry every partition's highest offset. Recommend a later
   spec: the offset batch needs its own design.
4. **Does connector 1.1.0 work with Pekko 1.2.1?** Its POM names
   `pekko-stream` 1.1.1 and `kafka-clients` 3.8.0, and Pekko keeps binary
   compatibility within 1.x. Recommend 1.1.0 and not 2.0.0-M1, which needs
   Pekko 2.

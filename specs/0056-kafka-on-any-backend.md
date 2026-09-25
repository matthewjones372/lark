# 0056 — Kafka on any backend

## Problem

`lark-kafka` (0053, 0054) is Pekko-only. `Kafka.subscribe` wraps Pekko's
connector `Source`, and `runCommitting` ends on its `Committer.sink`, so a
pipeline over Kafka is refused by Forks and by TestStreams. A service that
chose Forks for its speed (0051), or a test that wants `TestStreams`, cannot
consume a topic.

## Not doing

- **No change to the operators.** `Committed`, the `Record` operators,
  `Decoder`, `divertLefts` and `absolve` keep their signatures.
- **No transactions, no manual partition assignment.** As 0053.
- **The connector does not go.** It prefetches with backpressure, batches
  commits asynchronously and drains; a service on Pekko may want all three.

## Shape

```kotlin
val placed: Run<ShopError, Done> =
    Kafka.consume(consumer, Topic("orders"))            // Stream<Nothing, Committed<ConsumerRecord<K, V>>>
        .mapRecord { it.value() }
        .mapRecordOrFail { order -> shop.place(order).bind() }   // blocking is fine: Forks runs it on a virtual thread
        .runCommitting()                                 // Run<ShopError, Long>: how many elements reached the end

placed.start(Forks())                                    // or PekkoStreams(system), or TestStreams(clock)
```

- Two modules. **`lark-kafka`** depends on `lark-stream` and `kafka-clients`
  only: `Committed`, the operators, `Decoder`, and the consumer loop.
  **`lark-kafka-pekko`** adds the connector: today's `Kafka.subscribe`, its
  `runCommitting(CommitterSettings)` and draining stop.
- `Kafka.consume(props, topics, key, value)` is `Stream.blocking` (0055)
  over one `KafkaConsumer`. `next` hands out the last poll's records one at a
  time and polls again when they run out. `wake` is `consumer.wakeup()`.
- `runCommitting()` marks each offset handled as its element reaches the end,
  and answers how many did. The consumer commits what is marked before each
  poll, in its rebalance listener for the partitions it loses, and in
  `close`, which the run's exit waits for (0055). Only the thread that polls
  touches the consumer.
- `Committed` holds a neutral `Position` and a `Handle`, behind a `@KafkaSpi`
  opt-in, so `lark-kafka-pekko` brings the connector's offsets in. Each
  source's records end on their own `runCommitting`; the other one is a
  defect naming the right one.
- The bytes form with a `Decoder` pair (0054) is written once, over either
  source.

## Why this shape

The consumer loop is what the Kafka client is built for, one thread doing
everything, and it is also the loop Forks runs. Committing on the polling
thread removes the commit sink's cross-thread hand-off. Keeping the connector
as a second module leaves Pekko services the backpressured prefetch and async
commits they would otherwise lose.

## Stack

- [ ] **`spec-0056-split`**: move the connector to `lark-kafka-pekko`, leaving
      the neutral core. Done when: every 0053/0054 test passes unchanged there.
- [ ] **`spec-0056-consume`**: `Kafka.consume`, `runCommitting()`, the
      rebalance listener. Done when: the subscribe, operator, decode and
      routing tests pass on Forks and on Pekko against one broker.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Keep the connector at all?** Answered: yes, as `lark-kafka-pekko`.
2. **`mapParRecord` on Forks?** Answered: shipped anyway, and 0051 has since
   landed, so Forks runs it and `divertLefts`, which ConsumeTest holds.
3. **`restartOnDefect` on Forks?** Answered: 0052 has since given Forks a
   clock, so it runs there; a read that failed closes its consumer before the
   restart opens the next, which ConsumeTest holds on Forks and on Pekko.
4. **Poll timeout?** Answered: 100 ms by default, as `consume`'s
   `pollTimeout` parameter.

# 0193 — Kafka on lark-junit

## Problem

Spec 0191 recommends moving the rest of the repo to `lark-junit`, module by module, now that spec 0192 has
closed the gaps the pilots found. `lark-kafka` and `lark-kafka-pekko` come first. Each has its own copy
of `PekkoActorSystem`, the JUnit 5 extension that 0191 retired from `lark-stream-pekko`, and its own
`KafkaBroker`, an extension that starts an embedded broker per class. Between them they have 13 classes
on a broker or actor system, and their tests run one at a time on Jupiter.

## Not doing

- **No other module.** Each later module is its own spec, or one stack in a migration spec.
- **No broker shared across suites.** Spec 0191 found the per-class resource was not the cost. If the
  numbers here say otherwise, that is a separate spec.
- **No change to `ThreadsOnTimeout`.** It is a Jupiter extension, and `ThreadsOnTimeoutTest` tests it, so
  both stay on Jupiter. So do `NoOtherDependenciesTest` in each module and `CommittedCompileTest`, which
  runs the compiler in the test JVM, as `DoesNotCompileTest` does.
- **No change to what a test checks**, or to any topic or group name.

## Shape

```kotlin
object ConsumeSuite : Suite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("lark-kafka-consume-test")

    init {
        backends.forEach { on ->
            test("the records a run handles are committed by the time it ends, and the group starts after them [$on]") { kit ->
                val topic = "orders-$on"
                kit.broker.send(topic, "1", "2", "3", "4", "5")
                strings(kit, "placing-$on", topic).take(5).runCommitting().run(kit.backend(on)).settled() shouldBe Exit.Done(5L)
            }
        }
    }
}
```

- **The broker becomes a `Resource`.** `kafkaBroker()`, in each module's test sources, starts the embedded
  broker on acquire and stops it on release. It keeps today's port retry and its hook that turns a broker
  exit into a throw.
- **`KafkaKit`** is a broker and, where a suite needs one, a `PekkoSystem`, installed together.
  `backend(on)` answers `Forks()` or `PekkoStreams(...)` by name, as each class's private `backend` does today.
- **A parameterized test becomes one test per backend**, registered in a loop. Its name ends in
  `[Forks]` or `[Pekko]`, which keeps the backend visible where Jupiter showed `[1] Forks`.
- **Classes with no broker** (`Carried`, `GroupedRecord`, `Keeper`) become `Suite<Unit>` suites.
- **Concurrency:** tests run concurrently by default. Every test already uses topics and groups of its
  own. A test that fails under load gets `alone`, and is listed in the result.

## Why this shape

One resource per suite is the shape spec 0191 measured. A loop over backends is the plainest stand-in for
`@ParameterizedTest`. A parameterized-test feature for `lark-junit` would be more code to own than the
two backends need. Keeping `KafkaBroker`'s retry and exit hooks unchanged, inside the `Resource`, means
the fixes in it carry over rather than being relearned.

## Stack

- [ ] **`spec-0193-kafka-kit`** — in `lark-kafka`: `kafkaBroker()`, `kafkaKit()`, the build's
      `lark-junit` setup, and `KafkaBrokerSuite`, `PartitionsSuite`, `CarriedSuite`, `GroupedRecordSuite`
      and `KeeperSuite`.
- [ ] **`spec-0193-kafka-reads`** — `Read`, `CommitBeforePoll` and `Commits`.
- [ ] **`spec-0193-kafka-writes`** — `Produce` and `Transact`.
- [ ] **`spec-0193-kafka-consume`** — `Consume`, and `lark-kafka`'s `PekkoActorSystem` and `KafkaBroker`
      extensions deleted.
- [ ] **`spec-0193-kafka-pekko`** — in `lark-kafka-pekko`: the same kit and build setup, `Drain`,
      `Subscribe`, `Routing`, `Decode` and `CommittedOperators`, and its two extensions deleted.
      Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [ ] **`spec-0193-result`** — a `## Result` section: each module's test task before and after (median of
      5 warm runs), every `alone` added and why, and what to move next.
      Done when: the section holds measured numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-kafka:test :lark-kafka-pekko:test
```

## Open questions

Taken as recommended (maintainer, 2026-10-25: build it straight away).

1. ~~Do the classes with no broker move too?~~ Yes. They cost nothing to move, and the migration's end
   state is every class on `lark-junit` except those named in Not doing.
2. ~~How is a test per backend named?~~ The Jupiter name, with `[Forks]` or `[Pekko]` appended.
3. ~~Share one `KafkaKit` helper between the two modules?~~ No. Each module keeps its own test sources, as
   each keeps its own `KafkaBroker` today, so neither test classpath depends on the other.

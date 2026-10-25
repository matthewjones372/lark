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

- [x] **`spec-0193-default-timeout`** ([#582](https://github.com/matthewjones372/lark/pull/582)) — `lark-junit` reads `lark.junit.timeout.default` and holds each test
      with no `timeout` of its own, or its suite's, to it. The root build sets it to 60 seconds, as it sets
      Jupiter's `junit.jupiter.execution.timeout.default`. Found while drafting this spec: the suites that
      0189 and 0191 moved have run with no timeout since.
      Done when: a test past the default fails naming it, and one with its own longer timeout passes.
- [x] **`spec-0193-kafka-kit`** ([#590](https://github.com/matthewjones372/lark/pull/590)) — in `lark-kafka`: `kafkaBroker()`, `kafkaKit()`, the build's
      `lark-junit` setup, and `KafkaBrokerSuite`, `PartitionsSuite`, `CarriedSuite`, `GroupedRecordSuite`
      and `KeeperSuite`.
- [x] **`spec-0193-kafka-reads`** ([#592](https://github.com/matthewjones372/lark/pull/592)) — `Read`, `CommitBeforePoll` and `Commits`.
- [x] **`spec-0193-kafka-writes`** ([#595](https://github.com/matthewjones372/lark/pull/595)) — `Produce` and `Transact`.
- [x] **`spec-0193-kafka-consume`** ([#594](https://github.com/matthewjones372/lark/pull/594)) — `Consume`.
      `lark-kafka`'s `PekkoActorSystem` and `KafkaBroker` extensions were deleted after the last batch, in
      [#598](https://github.com/matthewjones372/lark/pull/598).
- [x] **`spec-0193-kafka-pekko`** ([#593](https://github.com/matthewjones372/lark/pull/593)) — in `lark-kafka-pekko`: the same kit and build setup, `Drain`,
      `Subscribe`, `Routing`, `Decode` and `CommittedOperators`, and its two extensions deleted.
      Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [x] **`spec-0193-result`** — a `## Result` section: each module's test task before and after (median of
      5 warm runs), every `alone` added and why, and what to move next.
      Done when: the section holds measured numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-kafka:test :lark-kafka-pekko:test
```

## Result

Measured on 2026-10-25, on a 4-core container, as `./gradlew :lark-kafka:test --rerun :lark-kafka-pekko:test
--rerun --profile`. Each side had one warm-up, then 5 runs: before on `main` ahead of #590, after on `main` with
#593, #594 and #595. Gradle runs the two tasks side by side. Medians:

| | Before (Jupiter) | After (`lark`) |
|---|---|---|
| `:lark-kafka:test` task | 91.27 s | 64.67 s (−29%) |
| `:lark-kafka-pekko:test` task | 71.32 s | 66.72 s (−6%) |
| Whole command | 93.55 s | 68.80 s (−26%) |

Task runs, `lark-kafka`: 91.27, 91.45, 90.75, 89.71, 91.52 s before; 64.50, 64.52, 64.73, 64.67, 65.96 s after.
`lark-kafka-pekko`: 72.40, 72.11, 70.94, 71.26, 71.32 s before; 66.35, 68.03, 65.80, 66.72, 67.09 s after.

- **Where `lark-kafka` gained.** Its suites hold most of their time in a few slow tests: `Commits` (35.6 s of
  case time), `Consume` (15.3 s), `Produce` (10.7 s) and `Transact` (8.2 s). Each now runs its tests at once
  against one broker, and every test uses its own topics and groups. Spec 0191 gained a tenth; this module
  gained over a quarter, because its time is broker round trips rather than compiles.
- **Why `lark-kafka-pekko` gained little.** One test, `DecodeSuite`'s "after restartOnDefect the record whose
  decoder was down is read again", takes 31.4 s on its own, about half the task. `CommittedCompileTest`, still
  on Jupiter, takes 13.8 s. Neither is shortened by running tests together. The 31 s test is worth a look in its
  own right: it is most likely waiting out a consumer-group timeout.
- **No `alone` or `concurrent = false`** was needed in either module. No test failed under concurrency, on
  either machine or in CI.
- **Lines:** about 40 files in the two modules, most of them `*Test.kt` renamed to `*Suite.kt`.
- **Gaps:**
  - **No timeout default.** The build's 60-second Jupiter default never reached lark suites. #582 fixed it
    for every module, including those moved by 0189 and 0191.
  - **Missing matchers.** `lark-junit` lacked `shouldContainAll` and the map matchers (`shouldContainKey`,
    `shouldNotContainKey`, a map's `shouldContain`); #590 added them. A map `shouldContainAll` is still
    missing: `lark-kafka-pekko` writes `tags.entries shouldContainAll expected.entries`.
- **Surprises:**
  - **The module move.** `main` moved every module into folders (`integrations/lark-kafka`) while the stack
    was open, and each branch needed a merge. Rename conflicts only; nothing was lost.
  - **A shared-state trap, caught in time.** `GroupedRecordTest` kept a mutable queue in a class field, which a
    suite object would have shared between tests; it is made per test now. Nothing else had state in a field.
  - **A stale local build.** After branch switches, Gradle's incremental compile reported errors that a clean
  compile did not. A `--rerun-tasks` build is the one to trust before pushing.
  - **A spec number taken twice.** #588 merged with "spec 0193" in its title, for unrelated lark-assert work,
    after this spec had taken the number.
- **What to move next.** The remaining Jupiter modules in the same way, one stack each, starting with those
  whose tests wait on I/O (actors, the cluster, the stream backends), where running tests together pays most.
  The one-off long tests, like `DecodeSuite`'s 31 s, are worth a fix of their own whichever runner they are on.

## Open questions

Taken as recommended (maintainer, 2026-10-25: build it straight away).

1. ~~Do the classes with no broker move too?~~ Yes. They cost nothing to move, and the migration's end
   state is every class on `lark-junit` except those named in Not doing.
2. ~~How is a test per backend named?~~ The Jupiter name, with `[Forks]` or `[Pekko]` appended.
3. ~~Share one `KafkaKit` helper between the two modules?~~ No. Each module keeps its own test sources, as
   each keeps its own `KafkaBroker` today, so neither test classpath depends on the other.

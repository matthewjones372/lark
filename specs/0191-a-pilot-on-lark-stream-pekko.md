# 0191 — A pilot on lark-stream-pekko

## Problem

Spec 0189's pilot moved `lark-pekko` to `lark-junit` and recommended `lark-stream-pekko` next. Spec
0190 then added the matchers that pilot found missing. `lark-stream-pekko` has 192 tests in 31 files.
25 of its classes each start their own `ActorSystem` through a JUnit 5 extension,
`PekkoActorSystem`, and run their tests one at a time.

None of its tests use Kotest. They use lark-assert, and two of its matchers have no value-returning
version yet:

- `withClue`, used in 27 files.
- `shouldBeInstanceOf`, used in 12, often to narrow a value and then check it.

This pilot moves the 25 classes and records what that cost and what it showed. That decides whether the
rest of the repo moves.

## Not doing

- **No other module.** The wider migration is its own spec, after this one.
- **No actor system shared across suites.** Spec 0174 kept `shared` to one suite. A module-wide
  resource is a separate spec, if the numbers here ask for one.
- **No moving the four classes without an actor system:** `BackendTest`, `DoesNotCompileTest`,
  `FunctionalStyleTest` and `NoOtherDependenciesTest`. They stay on Jupiter.
- **No rewriting what a test checks.** Each test keeps its name, word for word, and its assertions.
  Only their form changes.

## Shape

```kotlin
object FlattenSuite : Suite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("lark-stream-flatten-test")

    init {
        test("a stream of cursors becomes a stream of pages, in order") { pekko ->
            val pages = Stream.from(listOf(1, 3)).flatMapConcat { page(it) }.collected(pekko)
            withClue("no toSource and no Either in sight") {
                pages shouldBe Exit.Done(listOf("1-a", "1-b", "3-a", "3-b"))
            }
        }
    }
}
```

- **The system:** `pekkoSystem(name, config)`, in the test sources, installs an `ActorTestKit` and
  shuts it down on release. It replaces `PekkoActorSystem`, which is deleted with the last suite to
  move.
- **The clue:** `withClue(clue) { … }` in `lark-junit` returns the block's `Expectations`, with the clue
  above each failure's message.
- **The narrowing:** `x.shouldBeInstanceOf<T> { t -> … }` in `lark-junit` holds when `x` is a `T`, and
  then checks `t`, as `shouldThrow` does.
- **Concurrency:** tests run concurrently by default. Two suites start with `concurrent = false`:
  - `DefectTest`'s `LoggingTestKit` expectations match log lines that more than one test writes.
  - `RestartDelayTest` drives a `ManualTime` clock.

  Any other suite or test that fails under load gets `alone`, and each one is listed in the result.

## Why this shape

Keeping one system per suite, as today, means any change in time comes from running each suite's tests
concurrently. Neither a new lifetime nor fewer systems is mixed in. A module-wide system could save
more, but it needs a resource that spans suites, which spec 0174 left out. Measuring this first tells
us whether that spec is worth writing.

Adding `withClue` and `shouldBeInstanceOf` first costs one small PR. The alternative is to keep
lark-assert's throwing versions inside the suites: a throwing check is still allowed in a test. But
then a failed clue is reported as an error rather than a failure, and the checks after it go
unreported, in nearly every file.

## Stack

- [ ] **`spec-0191-clues`** — `withClue` and `shouldBeInstanceOf` in `lark-junit`, as values.
      Done when: tests show the clue above a failure, and a narrowed check that doesn't run on a miss.
- [ ] **`spec-0191-system`** — `pekkoSystem`, the test build's `lark-junit` plugin setup, and
      `ExitGuard`, `MapError`, `Bounds`, `Flatten`, `Hooked` and `Builders` moved.
      Done when: those suites report the same test names, all passing, under `lark`.
- [ ] **`spec-0191-batch-2`** — `Fanin`, `OrFailIfEmpty`, `Running` and `Sources`.
- [ ] **`spec-0191-batch-3`** — `AwaitExit`, `Pipe`, `Batching` and `Restart`.
- [ ] **`spec-0191-batch-4`** — `FromStage`, `BlockingOnPekko` and `RaiseInStream`.
- [ ] **`spec-0191-batch-5`** — `RunWith` and `Stream`.
- [ ] **`spec-0191-sequential`** — `Defect` and `RestartDelay`, each with `concurrent = false`.
- [ ] **`spec-0191-readme`** — `ReadmeExample`.
- [ ] **`spec-0191-mappar`** — `MapPar`.
- [ ] **`spec-0191-pipe-operators`** — `PipeOperators`.
- [ ] **`spec-0191-operators`** — `Operators`, and `PekkoActorSystem` deleted.
      Done when (each batch): the moved suites report the same test names, all passing, under `lark`.
- [ ] **`spec-0191-result`** — a `## Result` section in this spec. It gives:
      - `:lark-stream-pekko:test`'s wall time before and after, as the median of 5 warm runs;
      - the test-case total from the JUnit XML;
      - every `alone` or `concurrent = false` added, and why;
      - the matchers still missing;
      - a recommendation on the wider migration and on a module-wide resource.
      Done when: the section holds measured numbers and a recommendation.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-stream-pekko:test   # 192 tests: 171 on the lark engine, 21 on Jupiter
```

## Open questions

1. **Add `withClue` and `shouldBeInstanceOf` to `lark-junit` first, or keep lark-assert's throwing
   versions in the suites?** Recommend adding them first, for the reason above.
2. **One actor system per suite, as today, or one for the whole module?** Recommend one per suite for
   this pilot, with the module-wide question answered by the result.
3. **Which suites start out sequential?** Recommend only `DefectTest` and `RestartDelayTest`, for the
   reasons in Shape. Any other gets `alone` only after it fails under load, and is listed.
4. **Do the four classes without an actor system stay on Jupiter?** Recommend yes. They gain nothing
   from a shared resource. `DoesNotCompileTest` runs the compiler in the test JVM and already needs a
   2 GB heap, so running its 13 tests concurrently could exhaust it.

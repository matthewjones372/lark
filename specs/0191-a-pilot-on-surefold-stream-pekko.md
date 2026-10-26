# 0191 — A pilot on surefold-stream-pekko

## Problem

Spec 0189's pilot moved `surefold-pekko` to `surefold-junit` and recommended `surefold-stream-pekko` next. Spec
0190 then added the matchers that pilot found missing. `surefold-stream-pekko` has 192 tests in 31 files.
25 of its classes each start their own `ActorSystem` through a JUnit 5 extension,
`PekkoActorSystem`, and run their tests one at a time.

None of its tests use Kotest. They use surefold-assert, and two of its matchers have no value-returning
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
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-flatten-test")

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
- **The clue:** `withClue(clue) { … }` in `surefold-junit` returns the block's `Expectations`, with the clue
  above each failure's message.
- **The narrowing:** `x.shouldBeInstanceOf<T> { t -> … }` in `surefold-junit` holds when `x` is a `T`, and
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
surefold-assert's throwing versions inside the suites: a throwing check is still allowed in a test. But
then a failed clue is reported as an error rather than a failure, and the checks after it go
unreported, in nearly every file.

## Stack

- [x] **`spec-0191-clues`** ([#560](https://github.com/matthewjones372/lark/pull/560)) — `withClue` and `shouldBeInstanceOf` in `surefold-junit`, as values.
      Done when: tests show the clue above a failure, and a narrowed check that doesn't run on a miss.
- [x] **`spec-0191-system`** ([#561](https://github.com/matthewjones372/lark/pull/561)) — `pekkoSystem`, the test build's `surefold-junit` plugin setup, and
      `ExitGuard`, `MapError`, `Bounds`, `Flatten`, `Hooked` and `Builders` moved.
      Done when: those suites report the same test names, all passing, under `surefold`.
- [x] **`spec-0191-batch-2`** ([#562](https://github.com/matthewjones372/lark/pull/562)) — `Fanin`, `OrFailIfEmpty`, `Running` and `Sources`.
- [x] **`spec-0191-batch-3`** ([#564](https://github.com/matthewjones372/lark/pull/564)) — `AwaitExit`, `Pipe`, `Batching` and `Restart`.
- [x] **`spec-0191-batch-4`** ([#563](https://github.com/matthewjones372/lark/pull/563)) — `FromStage`, `BlockingOnPekko` and `RaiseInStream`.
- [x] **`spec-0191-batch-5`** ([#565](https://github.com/matthewjones372/lark/pull/565)) — `RunWith` and `Stream`.
- [x] **`spec-0191-sequential`** ([#569](https://github.com/matthewjones372/lark/pull/569)) — `Defect` and `RestartDelay`, each with `concurrent = false`.
- [x] **`spec-0191-readme`** ([#566](https://github.com/matthewjones372/lark/pull/566)) — `ReadmeExample`.
- [x] **`spec-0191-mappar`** ([#568](https://github.com/matthewjones372/lark/pull/568)) — `MapPar`.
- [x] **`spec-0191-pipe-operators`** ([#570](https://github.com/matthewjones372/lark/pull/570)) — `PipeOperators`.
- [x] **`spec-0191-operators`** ([#567](https://github.com/matthewjones372/lark/pull/567)) — `Operators`. `PekkoActorSystem` was deleted after the
      last batch, in [#571](https://github.com/matthewjones372/lark/pull/571).
      Done when (each batch): the moved suites report the same test names, all passing, under `surefold`.
- [x] **`spec-0191-result`** — a `## Result` section in this spec. It gives:
      - `:surefold-stream-pekko:test`'s wall time before and after, as the median of 5 warm runs;
      - the test-case total from the JUnit XML;
      - every `alone` or `concurrent = false` added, and why;
      - the matchers still missing;
      - a recommendation on the wider migration and on a module-wide resource.
      Done when: the section holds measured numbers and a recommendation.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-stream-pekko:test   # 192 tests: 171 on the surefold engine, 21 on Jupiter
```

## Result

Measured on 2026-10-24, on a 4-core container, as `./gradlew :surefold-stream-pekko:test --rerun --profile`.
Each side had one warm-up and then 5 runs: before on `61e8aa44`, the commit before any suite moved, and
after on `main` with #571. Medians:

| | Before (Jupiter) | After (171 on `surefold`) |
|---|---|---|
| `:surefold-stream-pekko:test` task (profile) | 34.26 s | 30.67 s |
| Whole command | 36.70 s | 33.64 s |
| Test-case total, all 192 (JUnit XML) | 27.50 s | 69.70 s |

Task runs: 33.17, 33.29, 35.17, 34.53, 34.26 s before; 30.44, 31.64, 30.71, 29.74, 30.67 s after.

- **The case total is not a time to compare.** Concurrent tests overlap, so their times add up past the
  task's. Most of the 69.70 s is `ReadmeExampleSuite`: its nine in-JVM compiles now run at once and slow
  each other down, to 57.86 s summed against 5.04 s before. `DoesNotCompileTest`, still on Jupiter, fell
  from 17.25 s to 5.62 s, because it no longer pays to warm the compiler. The task time is the measure: it
  is 3.6 s (10%) shorter.
- **The actor systems were never the cost.** Before, the two compiler classes took 22.29 s of case time
  (`DoesNotCompileTest` 17.25 s, `ReadmeExampleTest` 5.04 s). The other 170 moved tests took 5.10 s
  between them. Sharing one system across the module could only save part of the second number.
- **Lines:** 52 files, +3197 / -3327 in the module, most of it `*Test.kt` renamed to `*Suite.kt`.
- **Sequential suites:** `DefectSuite` and `RestartDelaySuite`, each with `concurrent = false`, for the
  reasons in Shape. No test needed `alone`. `MapParSuite`, the most timing-sensitive, also passed three
  further runs on a loaded machine.
- **Matchers still missing in `surefold-junit`, and what stood in:**
  - `shouldContainExactlyInAnyOrder` (4): `.sorted()` and `shouldBe`.
  - `shouldNotBeNull` (2): `shouldNotBe null`, or `expect(x != null)`.
  - `shouldBeEmpty` (1): `shouldBe emptyList()`.
  - The comparisons `shouldBeLessThan`, `shouldBeGreaterThan` and `shouldBeGreaterThanOrEqual` (3):
    `expect(a < b)` and the like.
- **Surprises:**
  - **State in a suite is shared.** Jupiter makes a class instance per test, but a suite is one `object`.
    `DefectTest` kept an exception in a field. Once it was a suite, every test attached its own "died on …"
    note to the same exception, and three tests failed until each test made a fresh one. Any class that
    keeps mutable state in fields has the same trap, and the guide doesn't say so yet.
  - **`@TempDir` has no equivalent.** `ReadmeExampleSuite` now makes and deletes a directory for each
    compile.
  - **Pekko's own testkit still throws.** `TestSink` probes and `LoggingTestKit.expect` stay as they are,
    so a failure there is reported as an error, not a failure. A probe's `expectNextN` returns a Scala
    `Seq`, which needs `CollectionConverters.asJava` before a `shouldBe` against a Kotlin list.
  - **A test with steps reads differently.** A check before the step under test, such as a latch await,
    becomes a `val` joined with `and` at the end. A failed `expect` no longer stops the test, so a check
    the next line depends on needs `shouldBeInstanceOf { … }` or `shouldThrow { … }` to guard it.
- **Recommendation: move the rest of the repo, module by module.** Neither pilot met a test that
  couldn't move, and nothing beyond the two expected suites needed to run alone. The time saved is
  modest, a tenth here. The case for moving is the one in spec 0174: shared resources, per-test logs and
  checks as values.
  - First, one small PR for the missing matchers above, and a section in `docs/testing.md` on state in
    a suite object.
  - **No module-wide resource for now.** Here the actor systems cost little against the compilers. If a
    later module starts something slow per class, such as a broker or a database container, revisit it
    then.
  - The compiler-heavy suites may run faster with fewer compiles at once: a `Limit.fixed(2)` on
    `ReadmeExampleSuite` is worth measuring.

## Open questions

Answered (maintainer, 2026-10-24): each as recommended.

1. ~~Add `withClue` and `shouldBeInstanceOf` to `surefold-junit` first?~~ Yes, first.
2. ~~One actor system per suite, or one for the whole module?~~ One per suite for this pilot. The
   result answers the module-wide question.
3. ~~Which suites start out sequential?~~ Only `DefectTest` and `RestartDelayTest`. Any other gets
   `alone` only after it fails under load, and is listed.
4. ~~Do the four classes without an actor system stay on Jupiter?~~ Yes.

# 0174 — Tests that run together

## Problem

The repo's tests run one at a time on JUnit Jupiter. Each class starts its own fixtures, such as an
`ActorSystem` or a broker. The tests use none of Lark's own concurrency, resources or log capture, and
every test's logs reach stderr whether it passed or not. Users of `lark-test` hit the same limits.

## Not doing

- **No new runner, report format or Gradle plugin.** Results go through the JUnit Platform, so Gradle's
  JUnit XML, the IDE's test tree and CI parsers read them unchanged.
- **No expectations here.** Expectations as values, and how they render, are spec 0175. Until it lands,
  a test body returns `Unit` and fails by throwing.
- **No moving the repo's own tests**, and no change to `story`, `lark-assert` or `lark`'s API.
  Making `lark-test` depend on `lark-assert` is a follow-up spec.
- **No resources shared across suites**, no property testing, and no `eventually` rewrite.

## Shape

A new module, `core/lark-junit`, depends on `lark` and `junit-platform-engine`. Its `TestEngine` is
registered in `META-INF/services`, so `useJUnitPlatform()` and the IDE find it next to Jupiter.

```kotlin
object OrderSuite : Suite<Database>(limit = Limit.fixed(8)) {
    override val shared: Resource<Database> = resource { install({ Database.start() }) { db, _ -> db.close() } }
    init {
        test("an order is saved once") { db -> db.save(order) }
        test("reads the thread", alone = true) { _ -> check(Thread.currentThread().isVirtual) }
    }
}
```

- **Suites** are Kotlin `object`s extending `Suite<R>`. A test's name is its display name and the
  basis of its JUnit unique ID.
- **Running:** `resourceScope { shared.bind() }` runs once per suite. Each test is a fork in a `flock`,
  capped by the suite's `Limit`, which defaults to `Limit.fixed(availableProcessors)`. Suites run one
  after another.
- **Isolation:** each fork binds its own `logger`, `metrics` and `clock`. They are `LarkLocal`s, so
  concurrent tests can't see each other's bindings.
- **Logs:** a failed test's captured lines go out as a `ReportEntry` and to stdout. A passing test
  prints nothing.

## Why this shape

The same suite in JUnit Jupiter today, and what the engine changes:

```kotlin
@Execution(ExecutionMode.CONCURRENT)   // plus junit.jupiter.execution.parallel.enabled=true
class OrderTest {
    companion object {
        lateinit var db: Database
        @JvmStatic @BeforeAll fun start() { db = Database.start() }
        @JvmStatic @AfterAll fun stop() { db.close() }
    }
    @Test fun `an order is saved once`() { db.save(order) }
    @Test @Isolated fun `reads the thread`() { check(Thread.currentThread().isVirtual) }
}
```

| | JUnit Jupiter | `lark-junit` |
|---|---|---|
| Threads | a `ForkJoinPool` of platform threads | a fork per test, on a virtual thread |
| Cap | one global `parallelism` property | a `Limit` per suite, fixed or adaptive |
| Shared resource | a `lateinit` static, set in `@BeforeAll` | a `Resource`, handed to each test |
| Acquire fails | the class fails, and its tests aren't run | every test fails with that cause |
| Partial release | written by hand in `@AfterAll` | in reverse order, by `resourceScope` |
| Clock, logger, metrics | process-wide, shared by every concurrent test | a `LarkLocal` binding per fork |
| Logs | interleaved on stdout, pass or fail | per test, and only on failure |

A `TestEngine` runs a suite's tests as forks and still reports each one as an ordinary JUnit test. A
Jupiter extension can't, because it would rely on Jupiter's own parallel mode, which uses platform
threads and can't hand a fork a resource. Running tests concurrently only inside a suite, never across
suites, keeps the per-class `ActorSystem` thread assertions reliable.

## Stack

- [x] **`spec-0174-engine`** ([#501](https://github.com/matthewjones372/lark/pull/501)) — the module, `Suite`, `object` discovery and sequential runs, plus
      `NoOtherDependenciesTest`.
      Done when: a sample suite's pass, fail and error show up by name in Gradle's JUnit XML.
- [x] **`spec-0174-concurrency`** ([#502](https://github.com/matthewjones372/lark/pull/502)) — tests run as forks under the suite's `Limit`. `alone = true` and
      `Suite(concurrent = false)` opt out.
      Done when: 8 latch-gated tests finish under `Limit.fixed(8)`, and run in order under `fixed(1)`.
- [x] **`spec-0174-shared`** ([#504](https://github.com/matthewjones372/lark/pull/504)) — `shared: Resource<R>`, acquired once and released after the last test.
      Done when: it is acquired once. If acquiring fails, every test fails with that cause, and what was
      already installed is released.
- [x] **`spec-0174-logs-on-failure`** ([#505](https://github.com/matthewjones372/lark/pull/505)) — logs captured per test and shown only on failure.
      Done when: a failing test's report entry holds its log lines, and a passing test's has none.
- [x] **`spec-0174-docs`** ([#516](https://github.com/matthewjones372/lark/pull/516)) — a `docs/testing.md` section with a `<!-- junit-suite -->` example, and a
      row in `docs/modules.md`.
      Done when: the guide test compiles the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
ls core/lark-junit/build/test-results/test/*.xml   # sample suites reported as JUnit XML
```

## Open questions

Answered (maintainer, 2026-10-23).

1. ~~Engine or Jupiter extension?~~ A JUnit Platform `TestEngine`.
2. ~~Do suites run in parallel with each other?~~ No. Only the tests inside a suite run concurrently.
3. ~~Shared resource lifetime?~~ One suite. Global resources would be a separate spec.
4. ~~Opting out?~~ Tests run concurrently by default. One test opts out with `alone = true`, and a
   whole suite with `concurrent = false`.
5. ~~Does `lark-test`'s promise stand?~~ Revisit it, in a separate spec.
6. ~~Do the repo's own tests move here after 0169?~~ Decide after a pilot in `lark-pekko`.

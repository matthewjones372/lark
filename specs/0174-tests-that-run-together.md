# 0174 — Tests that run together

## Problem

The repo's tests run one at a time on JUnit Jupiter. Each class starts its own fixtures, such as an
`ActorSystem` or a broker. The tests use none of Lark's own concurrency, resources or log capture. A
failed `shouldBe` throws, so a test stops at the first difference, and every test's logs reach
stderr whether it passed or not. Users of `lark-test` hit the same limits.

## Not doing

- **No new runner, report format or Gradle plugin.** Results go through the JUnit Platform, so Gradle's
  JUnit XML, the IDE's test tree and CI parsers read them unchanged.
- **No moving the repo's own tests**, and no change to `story`, `lark-assert` or `lark`'s API.
  Making `lark-test` depend on `lark-assert` is a follow-up spec.
- **No resources shared across suites**, no property testing, and no `eventually` rewrite.

## Shape

A new module, `core/lark-junit`, depends on `lark`, `lark-assert` and `junit-platform-engine`. Its
`TestEngine` is registered in `META-INF/services`, so `useJUnitPlatform()` and the IDE find it next to
Jupiter.

```kotlin
object OrderSuite : Suite<Database>(limit = Limit.fixed(8)) {
    override val shared: Resource<Database> = resource { install({ Database.start() }) { db, _ -> db.close() } }
    init {
        test("an order is saved once") { db ->
            db.save(order)
            expect(db.count() == 1) and expect(db.find(order.id) == order)
        }
        test("reads the thread", alone = true) { _ -> expect(Thread.currentThread().isVirtual) }
        test("does not compile") { db -> db.save(order) }   // Unit is not Expectations
    }
}
```

A failure shows each failing expectation as a diagram of its expression's values:

```
an order is saved once: 1 of 2 expectations failed
expect(db.count() == 1)
       |  |       |
       |  2       false
       Database(orders=2)
at OrderSuite.kt:6
```

- **Suites** are Kotlin `object`s extending `Suite<R>`. A test's name is its display name and the
  basis of its JUnit unique ID.
- **Running:** `resourceScope { shared.bind() }` runs once per suite. Each test is a fork in a `flock`,
  capped by the suite's `Limit`, which defaults to `Limit.fixed(availableProcessors)`.
- **Isolation:** each fork binds its own `logger`, `metrics` and `clock`. They are `LarkLocal`s, so
  concurrent tests can't see each other's bindings.
- **Expectations are values, as in ZIO Test.** A test body is `(R) -> Expectations`, so a body that
  checks nothing doesn't compile. `Expectations` is marked must-use, so with the repo's
  `allWarningsAsErrors`, an expectation built and dropped mid-body doesn't compile either. `and`
  keeps every failure, `or` passes if either side does, and `!` negates. A thrown exception
  fails the test as an error, not as a failure.
- **Rendering:** `expect(Boolean)` is rewritten by Kotlin's power-assert compiler plugin into the
  diagram above. Each failure is a `lark-assert` `AssertionFailedError` with the test's file and
  line, and a top-level `==` also carries expected and actual for IntelliJ's diff. Several failures
  are reported as one `MultipleFailuresError`. Without the plugin, `expect` still quotes the source
  line, as `lark-assert` does.
- **Logs:** a failed test's captured lines go out as a `ReportEntry` and to stdout. A passing test
  prints nothing.

## Why this shape

A `TestEngine` runs a suite's tests as forks and still reports each one as an ordinary JUnit test. A
Jupiter extension can't, because it would rely on Jupiter's own parallel mode, which uses platform
threads.

Expectations as values beat throwing checks: every failure is reported, and the compiler rejects a
test that checks nothing. For rendering, power-assert is recommended: it's JetBrains' own plugin, it
adds nothing to the runtime classpath, and one `expect` covers every check. Typed matchers
(`expect(x).toEqual(y)`) need no plugin but can't show subexpressions. A compiler plugin of Lark's own
(as `lark-app-compiler` is) could do what ZIO's macros do, but it's far more code to own.

## Stack

- [ ] **`spec-0174-engine`** — the module, `Suite`, `object` discovery and sequential runs, plus
      `NoOtherDependenciesTest`.
      Done when: a sample suite's pass, fail and error show up by name in Gradle's JUnit XML.
- [ ] **`spec-0174-concurrency`** — tests run as forks under the suite's `Limit`. `alone = true` and
      `Suite(concurrent = false)` opt out.
      Done when: 8 latch-gated tests finish under `Limit.fixed(8)`, and run in order under `fixed(1)`.
- [ ] **`spec-0174-shared`** — `shared: Resource<R>`, acquired once and released after the last test.
      Done when: it is acquired once. If acquiring fails, every test fails with that cause, and what was
      already installed is released.
- [ ] **`spec-0174-expectations`** — `Expectations`, `expect`, `and`/`or`/`!`, the `(R) -> Expectations`
      body, must-use, and reporting as `MultipleFailuresError`.
      Done when: two failing expectations are both reported, and a compile test shows that a `Unit` body and a
      dropped `expect` are both rejected.
- [ ] **`spec-0174-power-assert`** — the power-assert plugin applied to `expect`, plus expected and actual
      for a top-level `==`.
      Done when: a golden test matches the diagram above, and IntelliJ's diff opens on an `==` failure.
- [ ] **`spec-0174-logs-on-failure`** — logs captured per test and shown only on failure.
      Done when: a failing test's report entry holds its log lines, and a passing test's has none.
- [ ] **`spec-0174-docs`** — a `docs/testing.md` section with a `<!-- junit-suite -->` example, and a
      row in `docs/modules.md`.
      Done when: the guide test compiles the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
ls core/lark-junit/build/test-results/test/*.xml   # sample suites reported as JUnit XML
```

## Open questions

Answered (maintainer, 2026-10-23): 1. a `TestEngine`, not a Jupiter extension. 2. Suites run one
after another, and only the tests inside a suite run concurrently. 3. Shared resources last one suite.
4. Tests run concurrently by default, with `alone = true` and `concurrent = false` to opt out.
5. Revisit `lark-test`'s promise in a separate spec. 6. Decide on moving the repo's tests after a pilot
in `lark-pekko`.

Open (maintainer, 2026-10-23: expectations as values, as in ZIO Test, with a well-rendered error).

7. **What renders a failure: power-assert, Lark's own compiler plugin, or typed matchers?** Recommend
   power-assert. Users apply `kotlin("plugin.power-assert")` with `expect` listed in its functions.
   The guide shows how, and without the plugin you still get the quoted source line.
8. **How is a dropped expectation rejected?** Recommend Kotlin's return-value checker on
   `Expectations` (`-Xreturn-value-checker`), which the build turns into an error. Is it still
   experimental on 2.4.10? The `expectations` entry checks. If it is, detekt's `IgnoredReturnValue` rule is
   the fallback.
9. **Can a throwing `shouldBe` still appear inside a test?** Recommend yes, but it's reported as an
   error that stops the test, so the guide steers to `expect`.

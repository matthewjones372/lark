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
            expect(db.count() == 1) and expect.eq(order, db.find(order.id))
        }
        test("reads the thread", alone = true) { _ -> expect(Thread.currentThread().isVirtual) }
    }
}
```

- **Suites** are Kotlin `object`s extending `Suite<R>`. A test's name is its display name and the
  basis of its JUnit unique ID.
- **Running:** `resourceScope { shared.bind() }` runs once per suite. Each test is a fork in a `flock`,
  capped by the suite's `Limit`, which defaults to `Limit.fixed(availableProcessors)`.
- **Isolation:** each fork binds its own `logger`, `metrics` and `clock`. They are `LarkLocal`s, so
  concurrent tests can't see each other's bindings.
- **Expectations** are values. `and` keeps every failure, and `or` passes if either side does. A
  failure is reported as a `MultipleFailuresError` of `lark-assert`'s `AssertionFailedError`s, each with
  its file, line and source, so IntelliJ shows a diff for each one. A throwing `shouldBe` still works,
  as a single failure.
- **Logs:** a failed test's captured lines go out as a `ReportEntry` and to stdout. A passing test
  prints nothing.

## Why this shape

A `TestEngine` owns discovery, scheduling and reporting. It can run a suite's tests as forks and still
report each one as an ordinary JUnit test. A Jupiter extension is smaller, but it can't run sibling
`@Test`s concurrently itself. It would rely on Jupiter's global parallel mode, which uses platform
threads and can't hand a fork a resource. The engine is recommended.

Value expectations are recommended over throwing-only checks, because once the first check throws
nothing after it gets reported. Throwing checks are still allowed, so `lark-assert` stays the vocabulary.

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
- [ ] **`spec-0174-expectations`** — `Expectations`, `expect`, `and`/`or`, reported as `MultipleFailuresError`.
      Done when: a test with two failing expectations reports both, each with its own line.
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

1. **Engine or Jupiter extension?** Recommend the engine: it's the only one of the two that gets
   concurrent forks sharing a resource while still producing standard JUnit output.
2. **Do suites run in parallel with each other?** Recommend not at first. Concurrency is within a
   suite only, which keeps the per-class `ActorSystem` thread assertions honest.
3. **Shared resource lifetime: per suite, per module or per run?** Recommend per suite. Weaver-style
   global resources need suite ordering, and that's a separate spec.
4. **Opting out?** Recommend concurrent by default, `alone = true` per test and `concurrent = false`
   per suite. Load-sensitive tests such as `WireTest` (#489) and `UnorderedTest` (#490) opt out.
5. **Does `lark-test`'s promise stand?** Recommend yes: `story` stays assertion-agnostic and works
   inside a test here. Only `lark-junit` takes `lark-assert`.
6. **Do the repo's own tests move here after 0169?** Recommend deciding after a pilot in `lark-pekko`,
   which gains the most from a shared `ActorSystem`.

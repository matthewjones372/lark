# 0189 — A pilot on lark-junit

## Problem

The repo's own tests all run on JUnit Jupiter. Spec 0174 left open whether they should move to
`lark-junit`. The maintainer answered on 2026-10-23: decide after a pilot in `lark-pekko`, which gains
the most from a shared `ActorSystem`.

`lark-pekko` has two test classes besides its `NoOtherDependenciesTest`:

- `LarkDispatcherTest`: five tests on one `ActorSystem`. It is created in a `companion object` and
  stopped in `@AfterAll`, as AGENTS.md requires.
- `AwaitTest`: one test, with no shared resource.

The pilot moves both to `lark-junit` and records what that cost and what it showed.

## Not doing

- **No other module.** Whether the rest of the repo moves is decided after this, in its own spec.
- **No changes to `lark-junit` itself.** A missing matcher or a rough edge is a finding, written
  down in the result. It isn't fixed here.
- **No moving `NoOtherDependenciesTest`.** It is the same in every module, so it moves with the
  wider migration or not at all.
- **No change to AGENTS.md's actor-system rule** until the wider migration is decided.

## Shape

```kotlin
object LarkDispatcherSuite : Suite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = resource {
        install({ ActorSystem.create("lark-dispatcher-test", ConfigFactory.parseString(DISPATCHERS)) }) { system, _ ->
            system.terminate()
            Await.ready(system.whenTerminated(), 10.seconds.toScalaDuration())
        }
    }

    init {
        test("a virtual-thread-executor dispatcher is accepted, and parZip's branches run on virtual threads") { system ->
            val on = system.larkDispatcher()
            parZip(on = on, { Thread.currentThread().isVirtual }, { Thread.currentThread().isVirtual }) { a, b -> a to b } shouldBe (true to true)
        }
        // … the other four, with the same names
    }
}
```

- **Test names are kept word for word,** so the JUnit XML before and after can be compared test by
  test.
- **Matchers:** `lark-junit`'s `expect` and `shouldBe` where they fit. Where a value matcher is
  missing (`shouldContain`, `shouldBeSameInstanceAs`, `shouldThrow`), use `expect(...)` with
  power-assert, or `lark-assert`'s throwing check, and list each gap in the result.
- **Build:** `lark-pekko` takes `lark-junit` as a test dependency, with the power-assert plugin and
  `-Xreturn-value-checker=check` on its test compilation. Its main runtime classpath is unchanged.

## Why this shape

Moving the whole module, rather than one test class, makes the comparison clean: the module's test
task, before and after. Keeping each test's assertions the same rather than rewriting them means a
difference in the result comes from the framework, not from different checks.

## Stack

- [ ] **`spec-0189-pilot`** — `lark-pekko`'s test build takes `lark-junit`. `LarkDispatcherTest`
      becomes `LarkDispatcherSuite`, and `AwaitTest` becomes `AwaitSuite`. The Jupiter classes are
      removed.
      Done when: `:lark-pekko:test` reports the same 6 test names, all passing, under the `lark`
      engine, and `NoOtherDependenciesTest` still passes on Jupiter.
- [ ] **`spec-0189-result`** — a `## Result` section in this spec. It gives the before and after
      wall time of `:lark-pekko:test` (median of 5 warm runs), the lines changed, each matcher or
      feature that was missing, anything that surprised, and a recommendation on the wider migration.
      Done when: the section holds measured numbers and a recommendation.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-pekko:test   # 7 tests: 6 on the lark engine, 1 on Jupiter
```

## Result

Measured on 2026-10-24, on a 4-core container with JDK 25 tests, as `./gradlew :lark-pekko:test --rerun-tasks
--profile` after one warm-up: 5 runs before (`origin/main`) and 5 after (`spec-0189-pilot`). Medians:

| | Before (Jupiter) | After (`lark`) |
|---|---|---|
| `:lark-pekko:test` task (profile) | 6.32 s | 4.72 s |
| Whole command | 14.26 s | 12.14 s |
| Test-case total, all 7 (JUnit XML) | 2.41 s | 1.34 s |
| `:lark-pekko:compileTestKotlin` | 0.66 s | 0.56 s |

Runs: task 6.91, 6.75, 6.06, 6.28, 6.32 s before; 4.72, 4.92, 4.65, 5.18, 4.70 s after.
Most of the case-total drop is the first dispatcher test: 0.82 s on Jupiter, 0.08 s on `lark`. Creating
the `ActorSystem` falls outside the test cases on both engines, so the task time is the fairer measure.

- **Lines:** test sources +126 / -115 (two files rewritten); `lark-pekko/build.gradle.kts` +21.
- **Missing in `lark-junit`, and what stood in:**
  - `shouldContain` (4 checks): `expect("…" in message)`.
  - `shouldBeSameInstanceAs` (2): `expect(a === b)`.
  - `shouldThrow` (3): a `try { …; null } catch (e: Expected) { e }` that leaves the exception as a value,
    then `expect` on it. Each takes 5 lines where `shouldThrow` took 1.
  - `withClue` (1): `expect(cond) { "clue" }`. Power-assert prints the clue above the diagram, but the
    diagram also quotes the clue's lambda.
- **No `alone`:** all five dispatcher tests ran concurrently, the default, in 13 runs with no failure.
- **Surprises:**
  - Release works: the system has terminated when the suite ends (checked once, by hand).
  - Jupiter names a test `name()`, and `lark` names it `name`, so before and after XML match only once
    the `()` is stripped.
  - A test name that fits in a backticked `fun` is too long for `test("…") {` at 120 columns, so one
    call wraps.
  - Power-assert and the return-value checker cost no measurable compile time here.
- **Recommendation: not yet for the whole repo; go for `lark-stream-pekko` as the next pilot.** Nothing
  blocked this one, and the task ran faster. But every module leans on Kotest's `shouldThrow` and
  `shouldContain`. `lark-junit` should add first a `shouldThrow` that returns `Expectations` and hands
  over the exception, then `shouldContain` for strings and collections.

## Open questions

1. ~~**Is `lark-pekko` the right pilot?**~~ Answered: keep it, as recommended, as a cheap first step
   that shows the friction. `lark-stream-pekko`, with 25 test files that create an `ActorSystem`, is
   the next pilot; see Result.
2. ~~**What should fill the missing value matchers in the pilot?**~~ Answered: `expect(...)` with
   power-assert, so every check stays a value. Each gap is listed in Result.
3. ~~**Should the dispatcher tests run concurrently?**~~ Answered: yes, the default. None turned out
   load-sensitive, so none has `alone`.

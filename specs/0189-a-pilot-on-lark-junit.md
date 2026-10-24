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

## Open questions

1. **Is `lark-pekko` the right pilot?** It has one `ActorSystem` and six tests, so there's little
   for a shared resource or concurrency to speed up. `lark-stream-pekko`, with 25 test files that
   create an `ActorSystem`, is where those would pay. Recommend keeping `lark-pekko` as decided, as a
   cheap first step that shows the friction. The result names `lark-stream-pekko` as the next pilot
   if it goes well.
2. **What should fill the missing value matchers in the pilot?** Recommend `expect(...)` with
   power-assert, so every check stays a value, and listing each gap in the result.
3. **Should the dispatcher tests run concurrently?** They only read which thread a fork ran on, and
   don't count threads. Recommend running them concurrently, the default. If one turns out to be
   load-sensitive, it gets `alone`, and that goes in the result.

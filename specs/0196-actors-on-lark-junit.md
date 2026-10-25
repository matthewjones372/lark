# 0196 — Actors on lark-junit

## Problem

Spec 0193 moved the Kafka modules to `lark-junit` and recommended the I/O-bound modules next, starting with
actors. `lark-actor` has the most Jupiter tests left in the repo: 237 tests in 43 classes. Many wait on
something real, such as latches, sleeps or test clocks, and every class runs its tests one at a time.

Unlike Kafka's, these classes share no resource. Each test builds its own actors. So the only gain is from
running each class's tests concurrently, and the cost is any test that only passes alone.

The tests also use five lark-assert matchers that `lark-junit` lacks:
- `shouldContainExactly` (24 uses);
- `shouldBeRight()` with no argument, which narrows to the value;
- `shouldBeNull`;
- `shouldHaveSize`;
- `shouldBeInRange`.

Spec 0193 also left a map `shouldContainAll` missing.

## Not doing

- **No change to the journal contracts.** `JournalContract`, `SnapshotContract`, `FeedContract`,
  `OffsetContract` and `PruneContract` are published test fixtures that a service's own journal runs on
  Jupiter. `InMemoryJournalTest` and `InMemorySnapshotsTest`, which extend them, stay on Jupiter with them.
- **No other actor module.** `lark-actor-remote`, `-projection`, `-journal-jdbc` and the rest are later
  specs.
- **`ActorsGuideTest`**, which runs the compiler, and **`NoOtherDependenciesTest`** stay on Jupiter, as
  in every module so far.
- **No change to what a test checks.**

## Shape

```kotlin
object TimersSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a timer fires once, after its delay") { _ ->
            val clock = TestClock()
            // … as today, per test …
            fired shouldContainExactly listOf("tick")
        }
    }
}
```

- **The suites:** each class becomes a `Suite<Unit>` with its tests, names word for word.
- **Per-test state:** anything a test changes stays inside it, following "State in a suite". A class field
  that a test mutates is made per test.
- **Concurrency:** tests run concurrently by default. A test that fails only because tests run together
  gets `alone`, or its suite gets `concurrent = false`, with a comment saying why. Each one is listed in the
  result.
- **The matchers** come first, in `lark-junit`. Each fails with lark-assert's wording and carries the
  actual value:
  - `shouldContainExactly`, taking an iterable or `vararg` elements;
  - `shouldBeRight { a -> … }` and `shouldBeLeft { e -> … }`, which narrow as `shouldNotBeNull` does;
  - `shouldBeNull`;
  - `shouldHaveSize`;
  - `shouldBeInRange`;
  - a map's `shouldContainAll`.

## Why this shape

The shape is the one specs 0191 and 0193 measured, so the numbers compare. There is no shared resource to
design, which leaves concurrency as the only question. Starting every suite concurrent and backing off one
test at a time finds out exactly which tests depend on running alone. That is worth knowing in itself:
it is a hidden coupling, whichever runner they are on.

## Stack

- [x] **`spec-0196-matchers`** ([#604](https://github.com/matthewjones372/lark/pull/604)) — the six matchers above, in `lark-junit`, with tests.
- [x] **`spec-0196-actors-1`** to **`spec-0196-actors-6`** — `lark-actor`'s classes in six batches of
      about seven classes each, in alphabetical order. The build's `lark-junit` setup came first, in a
      shared base commit that every batch was cut from:
      [#614](https://github.com/matthewjones372/lark/pull/614),
      [#613](https://github.com/matthewjones372/lark/pull/613),
      [#612](https://github.com/matthewjones372/lark/pull/612),
      [#608](https://github.com/matthewjones372/lark/pull/608),
      [#610](https://github.com/matthewjones372/lark/pull/610) and
      [#609](https://github.com/matthewjones372/lark/pull/609).
      Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [x] **`spec-0196-result`** — a `## Result` section: `:lark-actor:test` before and after (median of 5 warm
      runs), every `alone` or `concurrent = false` added and why, and what to move next.
      Done when: the section holds measured numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor:test
```

## Result

Measured on 2026-10-25, on a 4-core container, as `./gradlew :lark-actor:test --rerun --profile`. Each side had one
warm-up, then 5 runs: before on `main` at #603, after on `main` with #614. Each side was measured twice, the second
pair back to back on a quiet machine. Medians:

| | Before (Jupiter) | After (228 on `lark`) |
|---|---|---|
| `:lark-actor:test` task, first pair | 22.25 s | 24.58 s |
| `:lark-actor:test` task, second pair | 23.49 s | 24.57 s |
| Whole command, first pair | 24.04 s | 26.87 s |

Task runs, first pair: 21.99, 22.46, 21.87, 22.25, 22.36 s before; 26.58, 25.62, 23.48, 24.58, 23.75 s after.
Second pair: 23.06, 22.85, 23.49, 23.64, 23.89 s before; 27.67, 26.08, 23.43, 23.24, 24.57 s after.

- **No time gained; about a second lost.** The moved tests were never the cost. In the JUnit XML their 39 classes
  took 3.63 s between them before and 3.46 s after. Each test is a few milliseconds, and suites run one after
  another, so running a suite's tests at once has little to overlap. `ActorsGuideTest`, which compiles the guides'
  examples and stays on Jupiter, is most of the task: 15.45 s of case time before and 16.96 s after. That 1.5 s rise
  accounts for most of the loss. The likely cause is that its compiles now load a larger test classpath, with
  `lark-junit` and power-assert on it, but that was not measured.
- **Tests:** the same 260 in every run: 228 in 39 suites on `lark`, and 32 on Jupiter. All 22 runs on `main` above
  passed.
- **No `alone` or `concurrent = false`** was needed. No test failed on CI or in any timed run.
- **Load-sensitive tests**, each seen once while six agents' builds ran at once (load average up to 54 on 4 cores):
  - `DurableProducerSuite`'s "on threads, a send while the journal is down answers Unwritten at once" checks each
    send takes under 500 ms. One send took 565 ms.
  - `InMemoryFeedTest`, still on Jupiter, hit the 60-second default timeout.
  - One run in 13 on batch 1's branch failed one test, and the next run overwrote its report before anyone saw which.

  None of these recurred on a quiet machine. The 500 ms check is the one to watch; `alone` is the fix if it does.
- **Shared state in fields**, as spec 0191 warned: eight classes kept mutable state in a field that a suite object
  would share. These were `Delivered`, `Entities`, `Persistent`, `PersistentDelivery`, `Prune`, `Router`, `Scoped`
  and `Topic`. Each test now makes its own.
- **Names:** every test keeps its name, except one `@ParameterizedTest` in `PersistentDelivery`. Its two cases,
  shown by Jupiter as `[1] 1` and `[2] 4`, are now two tests whose names end in ", batch 1" and ", batch 4". Spec
  0193 ended such names with `[value]` instead.
- **Matchers:** none missing once #604 landed. Loops of checks are joined with `reduce(Expectations::and)`, once in
  `Router` and through a small helper in `ShardedJournal`. An `allOf` in `lark-junit` would save both.
- **What to move next.** Moving pays where tests wait on something slow and can wait together, as `lark-kafka`'s
  did (−29%). It does not where each test takes milliseconds, as here. Move the modules whose tests wait on a network,
  `lark-actor-remote` and the cluster, next. Leave the fast unit-test modules until the migration's end state is
  what is wanted, not the time. `ActorsGuideTest` is this module's cost, as `ReadmeExampleSuite` was
  `lark-stream-pekko`'s, and is worth a look of its own.

## Open questions

Taken as recommended (maintainer, 2026-10-25: build it straight away).

1. ~~Move the journal contracts to `lark-junit` too?~~ No. A service's own journal runs them on Jupiter;
   moving them is a breaking change, for a later spec.
2. ~~A test that leans on a test clock or a latch shared across its own forks: `alone` or the whole suite
   sequential?~~ `alone` on the test, unless more than half the suite needs it.

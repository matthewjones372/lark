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

- [ ] **`spec-0196-matchers`** — the six matchers above, in `lark-junit`, with tests.
- [ ] **`spec-0196-actors-1`** to **`spec-0196-actors-6`** — `lark-actor`'s classes in six batches of
      about seven classes each, in alphabetical order, with the build's `lark-junit` setup in the first.
      Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [ ] **`spec-0196-result`** — a `## Result` section: `:lark-actor:test` before and after (median of 5 warm
      runs), every `alone` or `concurrent = false` added and why, and what to move next.
      Done when: the section holds measured numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor:test
```

## Open questions

Taken as recommended (maintainer, 2026-10-25: build it straight away).

1. ~~Move the journal contracts to `lark-junit` too?~~ No. A service's own journal runs them on Jupiter;
   moving them is a breaking change, for a later spec.
2. ~~A test that leans on a test clock or a latch shared across its own forks: `alone` or the whole suite
   sequential?~~ `alone` on the test, unless more than half the suite needs it.

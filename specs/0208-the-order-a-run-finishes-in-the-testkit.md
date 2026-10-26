# 0208 — The order a run finishes in, in the testkit

## Problem

One claim the engine makes is about order: a suite finishes only after every test it started has finished, so a
report never shows a suite done while one of its tests is still running. `SurefoldEngineTest` checks it by walking
JUnit's raw event list by index:

```kotlin
val events = run(selectClass(GatedSuite::class.java)).allEvents().list()
val suiteFinished = events.indexOfLast { it.testDescriptor.displayName == "GatedSuite" }

events.count { it.type == EventType.STARTED && it.testDescriptor.isTest } shouldBe 8
events.withIndex().filter { (_, e) -> e.type == EventType.FINISHED && e.testDescriptor.isTest }
    .map { it.index }.all { it < suiteFinished } shouldBe true
```

This is the last engine test that reads events by hand: specs 0204, 0206 and 0207 covered the rest. When it fails,
it says only `expected: true but was: false`, without naming the test that finished late.

`WaitTest` also still keeps a private `failureOf` helper that predates spec 0204. `surefold-junit-testkit` already has
that call.

## Not doing

- **No check on start order**, or on how tests interleave. Tests in a suite run concurrently, so only the finish
  order of a test against its suite is a claim.
- **No check on unique IDs.** `SurefoldEngineTest` reads one `uniqueId` to check its shape. That is a single
  descriptor property, not an order, and it reads plainly as it is.
- **No change to the engine.**

## Shape

```kotlin
val finished = run(selectClass(GatedSuite::class.java)).allEvents().finishOrder()

finished.take(8) shouldContainExactlyInAnyOrder (1..8).map { "gated $it" }
finished.drop(8) shouldBe listOf("GatedSuite", "Surefold")
```

- **`finishOrder()`** gives the display names of everything that finished, tests and containers alike, in the order
  they finished. Run on `allEvents()`, that is every test, then its suite, then the engine's root (`Surefold`).
- **The claim, in two lines:**
  - the first eight to finish are exactly the eight tests, in any order;
  - after them come the suite, then the engine.

  It is stronger than today's check, because it also says nothing else finished in between. A miss shows the order
  that did happen, so a late test is named.
- **`WaitTest`** uses the testkit's `failureOf`, and its private helper goes.
- **Docs:** the "Testing an aspect" section of `docs/testing.md` names `finishOrder()`.

## Why this shape

The order a run finishes in is a list, and the existing collection checks already say what a test wants of it:
which names come first, in any order, and what comes after. A list also reads in a failure report as what happened.

**Alternative:** a matcher, `events.shouldFinish(tests, before = "GatedSuite")`. It names the claim directly, but it
is one more call shaped for one test in the repo, and it says less than the two lines above, which also pin what
finishes after the suite. Recommended: the list.

## Stack

- [ ] **`spec-0208-finish-order`** — `finishOrder()` in the testkit, with a test that a suite's tests finish before
      it, using a sample whose tests finish in a known order.
      `SurefoldEngineTest`'s ordering test and `WaitTest`'s `failureOf` move to the testkit, and `docs/testing.md`
      names the call.
      Done when: `:surefold-junit-testkit:check` and `:surefold-junit:check` pass, and
      `grep -rn 'testDescriptor' core/surefold-junit/src/test` finds only the unique-ID test's read.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~Should `finishOrder()` include a skipped test, which never starts or finishes?~~ No. A skipped test doesn't
   finish, and `skipReasons()` already lists skipped tests.
2. ~~Should the testkit also offer `startOrder()`, for symmetry?~~ No. No test here claims a start order, and a
   suite's tests start concurrently.

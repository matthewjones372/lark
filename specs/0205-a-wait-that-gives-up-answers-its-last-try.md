# 0205 — A wait that gives up answers its last try

## Problem

In a `surefold-junit` suite, `eventually` gives up on checks that never held by answering their `Verdict.gaveUp`,
cast to the block's type (`gaveUpAs` in surefold-test's `Eventually.kt`). Before spec 0202 the cast was safe: a block
of checks returned `Expectations`. Since then checks return `Unit`, so a block that waits for a value and checks it
gets back an `Expectations` in place of that value:

```kotlin
val tally = eventually(5.seconds) { app.projection.tally().also { it.adopted shouldBe 1 } }
// gives up: ClassCastException: Expectations cannot be cast to Tally
```

The wait works while it succeeds. When it times out, the test fails with a `ClassCastException` at the call site
instead of the failure that says why. Petshop's `EventsSuite` waits this way in all four of its tests, and petshop's
`waitUntil` helper is still there because it passes a value on to later steps.

## Not doing

- **No change to a wait that holds, or to one whose last try threw.** The first answers its value as today. The
  second throws `GaveUp` with what was thrown, as today.
- **No change to how a gave-up failure reads.** It is still the last try's own failure, headed "gave up after N tries
  in T".
- **No change to `eventually` outside a suite.** There, a try that throws gives up as `GaveUp`, and a returned
  `Verdict` gives up as described below.

## Shape

```kotlin
test("the projection counts each adoption once") {
    val tally = eventually(5.seconds) { app.projection.tally().also { it.adopted shouldBe 1 } }
    tally.events shouldBe 1
}
```

- **Giving up answers the last try's value.** The checks of the last try are recorded, each headed "gave up after N
  tries in T", and `eventually` returns what that try returned. The test goes on, as it does after any check that
  failed. Above, a wait that gives up with `adopted` at 0 reports that failure and then also checks `events` on the
  last tally.
- **Steps too.** `Then("…").eventually(within) { … }` returns the last try's value in the same way.
- **The seam carries the value.** The try that didn't hold is thrown with its answer as well as its checks, so
  giving up needs no cast. `Verdict.gaveUp` keeps its job of heading the failures.
- **A returned `Verdict`** works as today: a block that returns one is still retried until it holds. When it gives
  up, `eventually` answers that verdict's `gaveUp`, now without a cast.

## Why this shape

A failed check in a suite doesn't stop the test, so a failed wait shouldn't either. The last try ran to completion
and has a value, and answering it costs nothing. Later checks then run on the latest state, so a report shows
everything that was wrong, not only the first thing.

**Alternative:** give up by stopping the test with a throw the engine recognises, and report only the recorded
failures. That needs no value, but it is the one check that stops a test, and it hides the checks after it.
Recommended: answer the last try's value.

## Stack

- [ ] **`spec-0205-last-try`** — surefold-test's `eventually` and surefold-junit's `KeepingHeldTries` carry the last
      try's answer, and giving up returns it. `WaitSuite` gains a wait over a value that gives up, with a later check
      on the value it answered. `WaitTest` checks that the test fails with the gave-up failure and the later check's
      failure, and no `ClassCastException`. The `eventually` section of `docs/testing.md` says what giving up answers.
      Done when: `:surefold-test:check` and `:surefold-junit:check` pass, and `grep -n 'as A' Eventually.kt` finds
      nothing.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~When the last try's value is answered, should the test log say the value came from a wait that gave up?~~ No.
   The gave-up failure already says so, and it is reported with the test.
2. ~~Should a story step that gives up still be marked `✗`, with the story going on?~~ Yes, as a step whose checks
   failed is today.

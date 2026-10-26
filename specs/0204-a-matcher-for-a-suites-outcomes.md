# 0204 — A matcher for a suite's outcomes

## Problem

The tests of surefold-junit's engine run a sample suite through JUnit's `EngineTestKit` and check what happened to
each test. Neither JUnit nor surefold-assert can say that in one call, so each test file writes it out by hand:

```kotlin
events.succeeded().list().map { it.testDescriptor.displayName }.sorted() shouldBe listOf(
    "holds once its check does, on the third try",
    "tries again after a throw",
    "waits as a story's step",
)
```

Because the same thing gets written in many places, 10 of the 16 files that use `EngineTestKit` keep a private
helper for it: `names()`, `failures`, `failuresOf`, `headingOf` or `thrownBy`. `SurefoldEngineTest` and
`AspectEngineTest` each define the same `Events.names()`. When an expected pass fails, the reader sees two lists
of names that don't match, not the failure that caused it; that failure is one `getRequiredPayload` away, and
nobody looks. The owner's review of `WaitTest`: we should have a matcher for this.

## Not doing

- **No change to the engine, or to `EngineTestKit`.** The tests still run a suite the way they do today.
- **No checks on report entries or logs.** Two files read a test's logs (`logsOf`); they keep their helpers for now
  (open question 2).
- **No suite-shaped engine tests.** These stay Jupiter tests, and the new checks throw as surefold-assert's do.

## Shape

```kotlin
val events = EngineTestKit.engine("surefold").selectors(selectClass(WaitSuite::class.java)).execute().testEvents()

events shouldHaveSucceeded listOf(
    "holds once its check does, on the third try",
    "tries again after a throw",
    "waits as a story's step",
)
events shouldHaveFailed listOf("gives up on a check that never holds")

val failed = events.failureOf("gives up on a check that never holds").shouldBeInstanceOf<AssertionFailedError>()
failed.expected.value shouldBe "Adopted"
```

- **`shouldHaveSucceeded`, `shouldHaveFailed`, `shouldHaveSkipped`** pass if exactly the tests named finished that
  way, in any order. A miss lists the expected names that ended some other way, each with its outcome. A test that
  failed when it was expected to pass is shown with its failure's message, so the reason is in the report. Tests
  that ended that way but weren't named are listed too. Expected and actual are carried, so IntelliJ can show a diff.
- **`failureOf(test)`** returns what the named test threw. If the test passed, was skipped, or doesn't exist, it
  throws, saying which.
- **`names()`** gives the display names of events, in the order they happened, for the few tests where order is the
  claim (`AspectEngineTest`'s sequential suite).
- **Where it lives:** `surefold-junit-testkit`, a new published module. It depends on `junit-platform-testkit` and
  `surefold-assert`, and it is the only one that does both. A user testing an aspect of their own adds it the way
  surefold-junit's tests do.

## Why this shape

The thing a test of the engine claims is which tests passed, failed or were skipped. A matcher that says that
reads as the claim, and when it fails it shows the failure, not two lists of names. A module keeps
`junit-platform-testkit` out of surefold-junit's runtime classpath and keeps surefold-assert free of JUnit.

**Alternative:** one shared `internal` file in surefold-junit's test sources. That fixes the repetition, but it is
the helper the owner asked not to write, and a user's aspect tests can't reach it. Recommended: the module.

## Stack

- [ ] **`spec-0204-testkit`** — the module, with `shouldHaveSucceeded`, `shouldHaveFailed`, `shouldHaveSkipped`,
      `failureOf` and `names`. Its tests run a small sample suite and check the matchers' passes and misses,
      including how a miss reads. `docs/testing.md` and `docs/modules.md` get the module.
      Done when: `:surefold-junit-testkit:check` passes, and a miss shows the unexpected failure's message.
- [ ] **`spec-0204-use`** — surefold-junit's engine tests and surefold-kafka's `ThreadsOnTimeoutTest` use the
      module, and their private helpers go, except `logsOf`.
      Done when: `grep -rn 'testDescriptor.displayName' --include=*.kt core integrations` finds nothing outside
      `surefold-junit-testkit`, and `./gradlew build` passes.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~A new module, or a `testFixtures` variant published with surefold-junit?~~ The module. The repo already ships its
   test support as modules (`surefold-stream-test`, `surefold-kafka-test`), and a module is simpler to depend on.
2. ~~Should it also check a test's logs, such as `events.logsOf(test) shouldContain "…"`?~~ Not yet. Two files read
   logs, and the shape of a log line (level, time) needs deciding first.
3. ~~Should `shouldHaveSucceeded` also accept `vararg` names?~~ Yes, beside the `Iterable`, as surefold-assert's
   collection matchers do.

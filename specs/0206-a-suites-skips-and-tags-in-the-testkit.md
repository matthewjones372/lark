# 0206 — A suite's skips and tags in the testkit

## Problem

`surefold-junit-testkit` (spec 0204) can say which tests passed, failed or were skipped, and what a failed one threw.
It can't say why a test was skipped, or what tags it carries. Those are the claims of the `ignore` and `tag` aspects.
So `AspectEngineTest`, which tests those aspects, still reads JUnit's events by hand:

```kotlin
tests.skipped().list().associate {
    it.testDescriptor.displayName to it.getRequiredPayload(String::class.java)
} shouldBe mapOf("speaks HTTP/3" to "no QUIC on CI", "ignored by the outer side" to "outer")

run(PartlyTaggedSuite::class.java).started().list()
    .associate { it.testDescriptor.displayName to it.testDescriptor.tags } shouldBe mapOf(
    "tagged" to setOf(TestTag.create("suite"), TestTag.create("io")),
    "untagged" to setOf(TestTag.create("suite")),
)
```

These are the last two `testDescriptor.displayName` reads that spec 0204 left for lack of a call. A user testing an
aspect of their own that skips or tags would write the same.

## Not doing

- **No checks on report entries or logs** (`logsOf`, the timing aspects' `attempts`). Spec 0204 left those for the
  shape of a log line to be decided first.
- **No check on where a suite's own event falls among its tests'** (`SurefoldEngineTest`'s `GatedSuite` ordering).
  That is a claim about the engine's event order, not about one test's outcome.
- **No change to `ignore`, `tag` or how the engine reports them.**

## Shape

```kotlin
val tests = run(IgnoringSuite::class.java)

tests shouldHaveSkipped mapOf("speaks HTTP/3" to "no QUIC on CI", "ignored by the outer side" to "outer")

run(PartlyTaggedSuite::class.java).started().tags() shouldBe mapOf(
    "tagged" to setOf("suite", "io"),
    "untagged" to setOf("suite"),
)
```

- **`shouldHaveSkipped(reasons: Map<String, String>)`** sits beside `shouldHaveSkipped(names)`. It passes if exactly
  the tests named were skipped, each for the reason given. A miss lists:
  - each test that ended another way, as `shouldHaveSkipped(names)` does;
  - each test skipped for another reason, with the reason it gave.

  Expected and actual are carried, so IntelliJ can show a diff.
- **`skipReasons()`** gives what each skipped test was skipped for, by name. It is the skipped counterpart of
  `failures()`, for a test that checks the reasons some other way.
- **`tags()`** gives each test's tags, by name, as tag names (`Set<String>`), not JUnit's `TestTag`. A test with no
  tags maps to an empty set. It reads the events given, so `started().tags()` covers the tests that ran.
- **Docs:** the "Testing an aspect" section of `docs/testing.md` names the three.

## Why this shape

Skip reasons get a matcher because a skip is an outcome, like a failure, and `shouldHaveSkipped` already says which
tests ended that way. Saying why in the same call keeps one claim in one place, and a miss can show "skipped, but for
'no Docker'". Tags aren't an outcome. They are data about a test, and `shouldBe` on a map of names already diffs
well, so they get an accessor and no matcher.

**Alternative:** a `shouldHaveTags` matcher as well. It would read as the claim, but it adds a call for one test in the
repo, and a miss reads no better than `shouldBe`'s diff of two maps. Recommended: the accessor alone.

## Stack

- [ ] **`spec-0206-skips-and-tags`** — `shouldHaveSkipped(Map)`, `skipReasons()` and `tags()` in the testkit, with
      tests for a pass, a wrong reason, an unexpected skip, and a test with no tags. `AspectEngineTest` uses them, and
      its two hand-written reads go. `docs/testing.md` names them.
      Done when: `:surefold-junit-testkit:check` and `:surefold-junit:check` pass, and
      `grep -n 'testDescriptor' core/surefold-junit/src/test/kotlin/io/github/matthewjones372/surefold/junit/AspectEngineTest.kt`
      finds only the timing test's report entries.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~Should `tags()` give tag names (`Set<String>`) or JUnit's `TestTag`?~~ Names. A test reads as the strings it passed
   to `tag(…)`, and the testkit's other calls answer plain names too.
2. ~~Should a skip with no reason given read as `""` or as absent from `skipReasons()`?~~ `""`. Every skipped test is in
   the map, so `skipReasons().keys` is the set of skipped tests.

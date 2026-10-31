# 0264 — Assertion failures that say what differed

## Problem

A failed surefold-assert check already builds a message with what was expected,
what was found, the test's file and line, and that line's source (spec 0169).
Gradle's default test logging does not print it. A suite failure looks like:

```
CourtBookingSuite > 50 holds at once over 10 slots never double-book FAILED
    org.opentest4j.AssertionFailedError at CourtBookingSuite.kt:300
```

The same line is what JUnit's own `assertEquals` produces under that default.
The person reading the console has to open the HTML report, pass `--info`, or
set `exceptionFormat = full` by hand before they see `expected: 40` /
`but was:  39`. The assertion did its job; the build hid the answer.

## Not doing

- **No change to what a check compares**, or to which matchers exist.
- **No field-by-field data-class diff** (still deferred from 0169).
- **No new assertion API**, and no change to `Miss`, `mismatchAt` or
  `withClue`.
- **No rewriting the multi-line message** into one line for Gradle's SHORT
  format: SHORT drops the message either way (verified on Gradle 9.7.1).
- **No forcing `testLogging` on every consumer** of the published
  `surefold-assert` artifact via the wiring plugin.

## Shape

This repo's root `subprojects` `Test` tasks print a failed test's exception
message without its stack:

```kotlin
tasks.withType<Test>().configureEach {
    // …
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = false
    }
}
```

A failed `shouldBe` then reads on the console as the Throwable already says:

```
CourtBookingSuite > 50 holds at once over 10 slots never double-book FAILED
    org.opentest4j.AssertionFailedError: expected: 40
    but was:  39
    at CourtBookingSuite.kt:300
        And("the other 40 are told the court was taken") { taken shouldBe 40 }
```

`docs/testing.md`'s Assertions section gains one sentence: the failure message
is on the console under this build's `testLogging`; a consumer of
`surefold-assert` alone sets the same knobs (or reads the HTML report) if
their build leaves Gradle's SHORT default.

A small test (or an existing message pin run under the root config) keeps the
claim: a deliberately failing check's console output from `:surefold-assert:test`
contains `expected:` and `but was:`.

## Why this shape

The message is already right. The missing piece is the runner's default.
`FULL` with `showStackTraces = false` prints the message and surefold-assert's
own `at File:line` / source quote, and skips the stack surefold-assert already
trimmed to the test frame. The alternative is leaving SHORT and telling people
to open the HTML report; that is what they do today, and it is why a failed
`shouldBe` looks silent.

**Alternative:** have the wiring plugin set the same `testLogging` for every
build that applies it. Recommended: not this entry — a published plugin
changing a consumer's console is a louder change than fixing this repo, and
the docs sentence covers consumers.

## Stack

- [ ] **`spec-0264-test-logging`** — root `Test` tasks use FULL without stack
      traces; `docs/testing.md` notes it; a check pins that a failed assert's
      console output names expected and actual.
      Done when: `./gradlew :surefold-assert:test` on a known failing check
      prints `expected:` and `but was:` under the FAILED line without
      `--info`, and the docs sentence is there.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A one-off failing `shouldBe` under `:surefold-assert:test` prints expected and
actual on the console under the FAILED line.

## Open questions

1. **Pin how?** A Gradle TestKit build that runs one failing test and asserts
   on its console is the honest check, but heavy for this. Recommended: a
   small TestKit fixture under `surefold-assert` or the root build, unless you
   would rather treat the `testLogging` block as verified by reading it and
   skip an automated pin.
2. **`showCauses`?** A `StoryFailed` wraps the assertion; FULL with causes
   prints the transcript and then the assertion. Recommended: leave
   `showCauses = true` so both stay visible.
3. **Wiring plugin for consumers?** Deferred; docs only in this entry.

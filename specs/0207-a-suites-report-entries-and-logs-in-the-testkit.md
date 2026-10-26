# 0207 — A suite's report entries and logs in the testkit

## Problem

surefold-junit publishes two kinds of JUnit report entry:
- **A failed test's log lines,** under the key `logs`. The engine's `Report.finished` renders each line as
  `LEVEL origin message key=value (cause)` and joins them with newlines.
- **A built-in aspect's reports,** such as `flaky`'s `attempts` (`"3 of 3"`). Aspects report through the internal
  `Aspect.reporting`, so only surefold-junit's own aspects publish entries.

`surefold-junit-testkit` can't read either. So three engine tests still read JUnit's events by hand:
- `StoryEngineTest` and `SurefoldEngineTest` each keep a private `logsOf`, and they differ. One answers one string
  per test, the other a list of strings per test.
- `AspectEngineTest` reads `keyValuePairs["attempts"]` inline.

```kotlin
private fun logsOf(events: EngineExecutionResults): Map<String, List<String>> =
    events.allEvents().reportingEntryPublished().list().groupBy(
        { it.testDescriptor.displayName },
        { it.getRequiredPayload(ReportEntry::class.java).keyValuePairs.getValue("logs") },
    )
// …
logs.getValue("fails first").single().lines() shouldBe listOf("INFO … fails first started", "WARN … about to fail")
```

Specs 0204 and 0206 left this for last, because the shape of a log line had to be decided first.

## Not doing

- **No change to what the engine publishes,** or to how a line is rendered. The testkit reads what Gradle, the IDE
  and the JUnit XML already show.
- **No structured log line** (level, origin, message and annotations as fields). The engine publishes text, and a
  test checks the text a reader would see.
- **No check on where a suite's own event falls among its tests'.** That is a claim about the engine's event order.
- **No matcher.** The claims are a list of lines, a line contained, or no entry at all, and `shouldBe`,
  `shouldContain` and `shouldNotContainKey` already read as those.

## Shape

```kotlin
val events = EngineTestKit.engine("surefold").selectors(selectClass(LoggingSuite::class.java)).execute().testEvents()

events.logs().getValue("fails first") shouldBe listOf(
    "INFO ${LoggingSuite::class.java.name} fails first started",
    "WARN ${LoggingSuite::class.java.name} fails first is about to fail",
)
events.logs() shouldNotContainKey "passes quietly"

timing.testEvents().reported("attempts") shouldBe
    mapOf("passes on its third attempt" to "3 of 3", "fails both of its attempts" to "2 of 2")
```

- **`logs()`** gives each test that published logs its lines, by name, one string per line as rendered. A test that
  published none, such as a passing one, is absent.
- **The decision this spec was waiting on:** a log line is the rendered text, `LEVEL origin message key=value
  (cause)`. That is what a reader of a failed test's report sees, and what these tests already check. If the
  rendering changes, these tests should change with it.
- **More than one entry:** if a test publishes more than one `logs` entry, for example a retried test failing on
  each attempt, its lines are joined in the order published.
- **`reported(key)`** gives what each test reported under `key`, by name, for any report entry surefold-junit publishes. A test that reported nothing under it is absent. If one test reported the key twice, the last
  value wins.
- **Any `Events`:** both read the events they're given, so `testEvents()` and `allEvents()` both work.
- **Docs:** the "Testing an aspect" section of `docs/testing.md` names both calls.

## Why this shape

The engine publishes logs as text, and every test that checks them checks text: the exact lines, a line it should
contain, or that a passing test published nothing. Reading them as one string per line answers all three, and needs
no model of a log line that the engine doesn't have. `reported(key)` covers every other entry, so a new built-in aspect that
reports is tested the same way as `flaky`. If `Aspect.reporting` is ever made public, a user's aspect is too.

**Alternative:** a structured `LogLine` with level, origin, message and annotations, parsed back from the rendered
text. It would let a test check one field, but it parses a format the engine owns only for display, and no test here
needs a field. Recommended: lines as text.

## Stack

- [ ] **`spec-0207-entries-and-logs`** — `logs()` and `reported(key)` in the testkit, with tests for:
      a failed test's lines, a passing test absent, a retried test's lines joined, and a key one test reported and
      another didn't.
      `StoryEngineTest`'s and `SurefoldEngineTest`'s `logsOf` go, and `AspectEngineTest` reads `attempts` with
      `reported`. `docs/testing.md` names both calls.
      Done when: `:surefold-junit-testkit:check` and `:surefold-junit:check` pass, and
      `grep -rn 'ReportEntry\|reportingEntryPublished' core/surefold-junit/src/test` finds nothing.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Should `logs()` split a test's entry into lines, or answer the joined text? Recommended: lines. Every check here
   is about a line, and `joinToString("\n")` gets the text back where it's wanted.
2. Should `reported(key)` answer only the last value per test, or every value (`Map<String, List<String>>`)?
   Recommended: the last value. No aspect here reports a key twice, and `logs()` already
   covers the one entry that can repeat.

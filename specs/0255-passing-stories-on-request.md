# 0255 — Passing stories on request

## Problem

Inside a `surefold-junit` suite, a story's transcript goes through the test's captured logger
(spec 0188). `Report.finished` prints and publishes those lines only when the test fails, so a passing
story's Given / When / Then is never seen. Checked on `main` (2026-10-28).

Spec 0188 chose that, so that a passing build is quiet. But someone writing or reviewing a story wants to
read it while it passes, to check that the steps say what the test does and to see what each step cost.
Today the only way to get it is to break the test. Doorlist hits this: about 400 suite tests, all stories,
and none of them can be read in the console.

## Not doing

- **No change to the default.** Without the flag, a passing story prints nothing, as 0188 says.
- **No change to failing tests.** Their logs, the story included, are published and printed as today.
- **No change to `story` under Jupiter.** It already prints every run.
- **No other log lines for a passing test.** The flag shows stories, not the test's whole log capture.

## Shape

```bash
./gradlew test -Psurefold.test.stories=always     # every story, passing ones too
./gradlew test                                     # as today: only failing tests' logs
```

- **`surefold.test.stories=always|failed`**, a system property read by `surefold-junit`. `failed`, the
  default, is today's behaviour.
- With `always`, a passing test's story transcripts are printed when it finishes, under the report's lock,
  so concurrent tests' stories never interleave. They are also published as a `story` report entry, so the
  IDE and the XML report show them.
- The wiring plugin passes `-Psurefold.test.stories` on to every `Test` task as a system property, as it
  passes `surefold.test.colour` (spec 0118). It is a task input, so changing it runs the tests again
  instead of reusing a cached result.
- Colour follows `surefold.test.colour`, as a story printed under Jupiter does.

## Why this shape

The story's lines need to be told apart from the other captured logs, so the transcript sink in
`tellingStories` tags them. Two ways to do that:

- **Recommended: a second list.** Transcripts are kept beside the test's logs, and `Report.finished`
  prints them when the flag is `always`. The logger's `LogLine` is not changed.
- **An annotation on the `LogLine`,** such as `story=true`, then a filter at the end. That is less code,
  but the marker would show up in any logger a test hands on.

A flag on the suite itself (`Suite(stories = Always)`) was considered. It is rejected because whether you
want to read a story is a choice about this run, not about the code.

## Stack

- [ ] **`spec-0255-stories-flag`** — `surefold-junit`: read `surefold.test.stories`, keep each test's
      transcripts beside its logs, and with `always` print them and publish a `story` entry for a passing test.
      Done when: a passing story prints nothing by default; with `always` its transcript is printed once and
      published as `story`; two concurrent passing stories print as two whole blocks; failing tests unchanged.
- [ ] **`spec-0255-stories-wiring`** — `surefold-app-gradle`: pass `-Psurefold.test.stories` on as a system
      property and task input; `docs/testing.md` documents the flag.
      Done when: `SurefoldWiringPluginTest` sees the property arrive, and sees it absent when it is not set.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-junit:test -Psurefold.test.stories=always   # stories in the console
```

## Open questions

1. **Should IntelliJ default to `always`?** `Colour` already detects IntelliJ. Recommend no: keep one
   default, and let the run configuration set the flag.
2. **Should the flag also accept a test-name filter?** For example `-Psurefold.test.stories=checkout*`.
   Recommend deferred. `--tests` already narrows a run to the tests you care about.
3. **Should a `story` report entry be published for passing tests even without the flag?** The IDE would
   then show it with no console noise. Recommend no, because it makes every XML report about 400 entries
   bigger; the flag adds it.
4. **What should the property be called?** Recommend `surefold.test.stories`, to sit beside
   `surefold.test.colour`.

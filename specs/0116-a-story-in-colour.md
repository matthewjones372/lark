# 0116 — A story in colour

## Problem

[0115](0115-a-test-that-reads-as-a-story.md) prints a story's steps as plain
text, one line each, with ✓ or ✗ and a duration. In an IDE's test console, a
failing step in a story of eight looks the same as the seven that passed until
you read every line. Colour is how a person at the IDE finds the ✗ without
reading.

## Not doing

- Colour in the failure message. The `AssertionError` that carries the
  transcript stays plain, because it ends up in JUnit XML, CI logs and bug
  reports, where escape codes are noise.
- Themes or configurable palettes.
- Anything except ANSI escape codes: no terminal library, no Jansi. `lark-test`
  stays `lark` and Kotest.
- Detecting a terminal's capabilities beyond on and off.

## Shape

No API. The same `story { }` from 0115 prints:

| Part | Style |
|---|---|
| `Story:` header | bold |
| ✓ and its step | green ✓, step in the default colour |
| ✗ and its step | red, bold |
| the failure under a ✗ | red |
| durations, tries | dim |

There is no line for a step that never ran. Steps are code, not a list
declared up front, so after a failure the story cannot know what came next.

A prototype runs in the petshop (`Story.kt`, matthewjones372/petshop#20) and
prints exactly this.

Whether colour is on is decided once per JVM, in this order:

1. `-Dlark.test.colour=always|never|auto`: `always` and `never` win outright.
2. `NO_COLOR` set to anything non-empty: off ([no-color.org](https://no-color.org)).
3. `FORCE_COLOR` set to anything non-empty: on.
4. Running under IntelliJ: on.
5. Otherwise: off.

## Why this shape

Off unless something says on, because a Gradle test worker has no console to
ask (`System.console()` is null there), and escape codes in a CI log that does
not render them are worse than no colour. `NO_COLOR` and `FORCE_COLOR` are the
two conventions other tools already honour, so a developer who set either for
another tool gets the same here. The alternative is on by default with
`NO_COLOR` to turn it off. Recommended against: the cost of being wrong falls on
CI logs nobody chose to colour.

Keeping the failure message plain means colour can never make the transcript
harder to read where it matters most. The prototype found one more reason:
JUnit's XML report cannot hold the escape character and writes `?` in its
place, so a coloured message would arrive as `?[31m` in every CI report.

**A Gradle test worker does not see the shell's environment.**
`FORCE_COLOR=1 ./gradlew test` set nothing in the JVM the tests ran in, so
rules 2 and 3 never fired from the command line. The prototype hands them on
in the build, and `lark-test` should say how:

```kotlin
tasks.test {
    listOf("FORCE_COLOR", "NO_COLOR").forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }
    providers.gradleProperty("lark.test.colour").orNull?.let { systemProperty("lark.test.colour", it) }
}
```

## Stack

Depends on 0115's `spec-0115-steps`.

- [ ] **`spec-0116-colour`** — the styles, the five rules deciding whether
      they are on, and the build snippet above in `lark-test`'s KDoc.
      Done when: output captured with `lark.test.colour=never` holds no escape
      code; with `always`, the ✗ line starts with red and bold; with
      `NO_COLOR=1` and `FORCE_COLOR=1` both set, it is off; and the thrown
      transcript holds no escape code in any mode.

## Acceptance

```bash
./gradlew :lark-test:test
```

Then a failing petshop story run from IntelliJ shows its ✗ in red.

## Open questions

- **How to tell IntelliJ is running the test.** A JUnit run from IntelliJ sets
  `idea.test.cyclic.buffer.size`, but a run delegated to Gradle may set nothing
  in the worker. Recommended: check that property, and document `FORCE_COLOR=1`
  in the run configuration for the delegated case. The prototype checks only
  that property and has not been tried in IntelliJ yet, so this still needs
  checking there before building.
- **Colour inside the failure line**, e.g. Kotest's `expected:<…>` green and
  `but was:<…>` red? That means parsing Kotest's message, which can change
  under us. Recommended: no; the whole line is red.
- **The build snippet: documented, or applied?** `lark-app-gradle` already
  configures consumers' builds for the wiring check, and could pass these
  through too. Recommended: document it here, and leave applying it to that
  plugin for a later spec.
- **Should `NO_COLOR` beat `-Dlark.test.colour=always`?** Recommended: no. An
  explicit property on this one run is more specific than an environment
  variable that may have been set for something else.

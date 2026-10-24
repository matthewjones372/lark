# 0176 — Test aspects

## Problem

Spec 0174's `lark-junit` lets a test say one thing about how it runs: `alone = true`. ZIO Test has a
general mechanism for this, aspects (`test(...) @@ flaky @@ timeout(5.seconds)`). In Jupiter the same
needs are annotations spread across three extensions, and some can't be expressed at all. The repo has
load-sensitive tests (`WireTest` in #489, `UnorderedTest` in #490) that nothing today runs many times to
prove they're stable. The maintainer wants ZIO-style aspects (2026-10-23).

## Not doing

- **No `@@`.** Kotlin can't define it, and a backticked `` `@@` `` reads worse than a name.
- **No OS, JDK or environment conditions**, and no `samples` or property aspects.
- **No aspects that change a test's body**, such as `around` or `before`. That's what `shared` is for.

## Shape

An `Aspect` is a value. `+` combines aspects, and the left one wraps the right one. A test or a suite
takes aspects as a parameter:

```kotlin
object WireSuite : Suite<Wire>(aspects = tag("io") + timeout(30.seconds)) {
    init {
        test("frames survive a slow reader", nonFlaky(50) + alone) { wire -> expect(wire.roundTrip()) }
        test("reconnects after a reset", flaky(3)) { wire -> expect(wire.reconnects()) }
        test("speaks HTTP/3", ignore("no QUIC on CI")) { _ -> expect(false) }
    }
}
```

| Aspect | What it does | Built on | Reported as |
|---|---|---|---|
| `flaky(n)` | retries up to `n` times, and passes if any attempt passes | `Schedule.retry` | passed, with a `ReportEntry` giving the attempts |
| `nonFlaky(n)` | runs `n` times, and every run must pass | a loop | failed on the first failing run, naming it |
| `timeout(d)` | fails the test if it runs longer than `d` | Lark's `timeout` | failed, naming `d` |
| `ignore(why)` | doesn't run the test | — | skipped, with `why` |
| `tag(name)` | a JUnit tag, so Gradle's `includeTags` and the IDE filter by it | `TestTag` | — |
| `alone` | nothing else in the suite runs alongside it | 0174's scheduler | — |

A suite's aspects wrap each of its tests' aspects.

## Why this shape

The options for where the aspects go:

- **A parameter: `test(name, flaky(3)) { }`.** The aspects are known when the test is registered, so
  discovery can read tags and `ignore` without running anything. Recommended.
- **Trailing infix: `test(name) { } with flaky(3)`.** Closest to ZIO's look, but `test` would have to
  return a handle that is mutated after registration. Also, `with` already has a different meaning in
  Kotlin.

Building aspects on Lark's own `Schedule` and `timeout` keeps their behaviour identical to the
production code they test.

## Stack

Builds on 0174's `spec-0174-concurrency`.

- [x] **`spec-0176-aspects`** ([#514](https://github.com/matthewjones372/lark/pull/514)) — `Aspect`, `+`, test and suite parameters, and `ignore`, `tag` and
      `alone`. The last one replaces 0174's `alone = true`.
      Done when: Gradle's `includeTags` selects a tagged test, an ignored test shows as skipped in the
      JUnit XML, and an `alone` test sees no other test running.
- [x] **`spec-0176-timing`** ([#515](https://github.com/matthewjones372/lark/pull/515)) — `timeout`, `flaky` and `nonFlaky`.
      Done when: `flaky(3)` passes a test that fails twice and records 3 attempts. `nonFlaky(50)` fails a
      test that fails one run in 10, and names that run. `timeout` interrupts a sleeping body.
- [x] **`spec-0176-docs`** ([#518](https://github.com/matthewjones372/lark/pull/518)) — the aspects table in `docs/testing.md`, with a compiled example.
      Done when: the guide test compiles the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-junit:test -PincludeTags=io   # only tagged suites run
```

## Open questions

1. **Parameter or trailing infix?** Recommend the parameter, for the discovery reason above.
2. **Does `alone` replace 0174's `alone = true`?** Recommend yes, so there's one way to say it. 0174
   would keep the parameter only until this spec's first entry lands.
3. **What does `a + b` mean?** Recommend that the left one wraps the right one. So
   `timeout(1.seconds) + flaky(3)` limits all the attempts together, and `flaky(3) + timeout(1.seconds)`
   limits each attempt.
4. **Should a test that passes under `flaky` be reported as passed?** Recommend passed, with the
   attempts in a `ReportEntry`. That way CI stays green, and the flake is still visible in the JUnit XML.

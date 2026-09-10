# 0018 — The log a fork carries

## Problem

A line logged inside a `parMap` cannot say which request it belongs to. The
branch runs on a thread of its own, and MDC — which is a `ThreadLocal` — is
empty there. So a service either threads a logger and a correlation id through
every function between the handler and the leaf, or it logs lines nobody can
join up.

Spec 0017 gives a fork something to inherit. This is the thing worth putting in
it first.

## Not doing

- **No logging backend.** An interface, and a default that writes a line to
  stderr. slf4j, Logback and the rest are an adapter module.
- **No tracing.** A span here is a log span with a duration. OpenTelemetry is
  its own module and its own decision.
- **No structured output format.** A `LogLine` is a value; rendering it is the
  backend's.

## Shape

```kotlin
fun logInfo(message: String)
fun logWarn(message: String)
fun logError(message: String, cause: Throwable? = null)

fun <A> logSpan(name: String, block: () -> A): A
fun <A> logAnnotated(vararg pairs: Pair<String, String>, block: () -> A): A
```

```kotlin
logAnnotated("correlation_id" to request.id) {
    logSpan("register") {
        parMap(request.items) { item -> validate(item) }   // every line carries the id
    }
}
```

The annotations and the logger both live in a `LarkLocal`, so the propagation
is 0017's and this spec adds no fork-site code.

```kotlin
fun interface Logger { fun log(line: LogLine) }

val logger: LarkLocal<Logger> = larkLocal { StderrLogger }
```

## Why this shape

Emission is write-only and cannot change control flow, which is what makes it
safe to leave ambient. A clock is not — it decides what a program does, so it
stays a value a test binds deliberately. The same rule says metrics may be
ambient later and a `Random` may not.

The alternative is a `Log` parameter every class takes. That is testable and
explicit, and it is also the boilerplate this exists to remove; a service can
still do it where it wants to assert on a specific class's output.

## Stack

- [ ] **`spec-0018-logger`** — `Logger`, `LogLine`, `LogLevel`, `StderrLogger`,
      the bound logger, and `logInfo`/`logWarn`/`logError`.
      Done when: a line logged inside a `parMap` branch reaches the bound logger.
- [ ] **`spec-0018-annotations`** — `logAnnotated`, `logSpan`, and a capturing
      logger for tests.
      Done when: a correlation id set outside a `parMap` is on every line each
      branch logs, and a span carries the duration it took.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `lark` hold the facade, or a `lark-log` module?** Recommend `lark`:
   an interface and a `LarkLocal` add nothing to `NoOtherDependenciesTest`'s
   classpath, and a fork carrying no log context is the thing being fixed.
2. **Does a raise or a throw log itself?** Recommend no. `zio.dev` argues the
   same: a typed error is already reported to its caller, and logging it as
   well is how one failure becomes three lines.
3. **Is the default logger stderr or nothing?** Recommend stderr, so a service
   that binds no logger still sees its own start-up.

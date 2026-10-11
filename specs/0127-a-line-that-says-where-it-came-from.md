# 0127 — A line that says where it came from

## Problem

`logInfo("registering $id")` builds a `LogLine` of level, message, time, cause
and annotations. Nothing records who called it. `StderrLogger` prints no source,
and `Slf4jLogger` writes every line to one SLF4J logger, `"lark"`, so `%logger`
reads `lark` on every line and a backend's `%C`/`%L` resolve to
`Slf4jLogger.say`. A service cannot tell which class wrote a line, and it cannot
set a level for one package's lines without setting it for all of them.

Today a service puts the class into the message by hand or binds an annotation
for it, and neither one reaches the backend's logger name.

## Not doing

- **No named-logger API.** `logInfo` stays a top-level function with no logger
  argument, and the 41 call sites stay as they are.
- **No method or line number.** Only the class name. A backend that wants `%L`
  works it out itself (see Open questions 3).
- **No level check before the line is built.** Spec 0032 Q1 still applies.

## Shape

```kotlin
data class LogLine(
    val level: LogLevel,
    val message: String,
    val at: Instant,
    val cause: Throwable? = null,
    val annotations: Map<String, String> = emptyMap(),
    val origin: String? = null,   // "com.acme.pets.Registry"
)

class Registry {
    fun register(id: String) = logInfo("registering $id")
}
// StderrLogger: 2026-10-11T… INFO com.acme.pets.Registry registering 42
// Slf4jLogger:  logged through LoggerFactory.getLogger("com.acme.pets.Registry")
```

- `log()` in `Logger.kt` fills `origin` with a `StackWalker`: the first frame
  whose class is not `LoggerKt`. That class keeps everything before its first
  `$`, so a lambda or a companion reports its enclosing class.
- Code that builds a `LogLine` directly (`Restarting`, `Forks`, `Timed`,
  `Remote`, `Backend`) passes its own class as `origin`.
- `Slf4jLogger` writes to `getLogger(line.origin ?: name)`. SLF4J caches loggers
  by name, so this adds no map of our own.
- `capturingLogs` keeps the field, so a test can assert on `origin`.

## Why this shape

The walk is automatic: every existing call site starts reporting its class and
none of them changes. It runs once per line, and it is lazy, so it stops at
the first frame outside `LoggerKt`, which is about two frames down. The
alternative is a logger value per class, `val log = larkLogger<Registry>()`. It
costs nothing at runtime, but it adds a second way to write a line and leaves
`logInfo` with no origin. I recommend the walk. Another option was to fix only
`Slf4jLogger`, using `LocationAwareLogger` with `LoggerKt` as the FQCN. That
corrects `%C` but still leaves `%logger` reading `lark`, and it does nothing for
stderr or for a captured line.

## Stack

- [ ] **`spec-0127-origin`** — `LogLine.origin`, filled by `log()` through a
      `StackWalker`. `StderrLogger` prints it.
      Done when: `logInfo` called from a class, from a lambda inside it, and
      from a top-level function each capture the expected `origin`.
- [ ] **`spec-0127-library-origins`** — the five direct `LogLine(...)` sites
      pass their own class.
      Done when: each site's existing test asserts its `origin`.
- [ ] **`spec-0127-slf4j-by-origin`** — `Slf4jLogger` names its SLF4J logger by
      `origin`. README and cookbook say so.
      Done when: a line from `Registry` reaches the logger named
      `…Registry`, and a line with no origin reaches `name`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does an explicit `Slf4jLogger("app")` still win?** As written, `origin`
   wins and `name` is only the fallback. A service that configured
   `<logger name="lark">` loses that config. Option: `name: String? = null`,
   where null means name by origin and an explicit name pins the old behaviour.
   Recommend that option.
2. **Should a top-level function report `RegistryKt` or the file name
   without the `Kt`?** Recommend `RegistryKt`, the class as the JVM names it,
   unchanged, so it matches what a stack trace prints.
3. **Should the adapter also fix `%C`/`%L`?** It can, by logging through
   `LocationAwareLogger` with `LoggerKt` as the boundary class. That is about
   ten lines, and it only helps backends that already compute caller data.
   Recommend leaving it out unless someone asks for it.
4. **Is the walk fast enough?** Recommend a JMH line in
   `lark-actor-benchmarks` before entry 1 merges, rather than guessing. If the
   walk costs too much, fall back to the per-class logger value.

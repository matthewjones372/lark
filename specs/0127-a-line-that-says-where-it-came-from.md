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

- **No change at the call site.** `logInfo` stays a top-level function with no
  logger argument, and the 41 call sites stay as they are.
- **No named-logger API.** There will be no `larkLogger<Registry>()`.
- **No method or line number.** Only the class.
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

inline fun logInfo(message: String) =
    logAt(LogLevel.Info, message, null, MethodHandles.lookup().lookupClass())

class Registry {
    companion object { fun start() = logInfo("up") }       // origin …Registry
    fun register(ids: List<String>) =
        ids.forEach { logInfo("registering $it") }           // origin …Registry
}
// StderrLogger: 2026-10-11T… INFO com.acme.pets.Registry registering 42
// Slf4jLogger:  logged through LoggerFactory.getLogger("com.acme.pets.Registry")
```

- The four `log*` functions become `inline`, so `lookupClass()` is evaluated at
  the call site and returns the calling class. `logAt` is `@PublishedApi
  internal`.
- `origin` is the **top-level** class: follow `enclosingClass` until there is
  none, so a companion, a nested class, an anonymous `object` and a local class
  all report the class that contains them. The name is cached once per class in
  a `ClassValue`, so each line costs one lookup and one map read, with no
  reflection.
- A top-level function reports its file class, for example `RegistryKt`.
- Code that builds a `LogLine` directly (`Restarting`, `Forks`, `Timed`,
  `Remote`, `Backend`) passes its own top-level class as `origin`.
- `Slf4jLogger(name: String? = null)` writes to `getLogger(line.origin)`. An
  explicit `name` keeps the single logger it writes to today. SLF4J caches
  loggers by name, so this adds no map of our own.

## Why this shape

Inlining captures the caller without changing any call site and without walking
the stack. Each line costs one `Lookup`, which the JIT can usually remove, plus
a `ClassValue` read. A `StackWalker` would give the same answer at a measurable
cost on every line, including lines whose level is off. A logger value per class
costs nothing at runtime, but it adds a second way to write a line, which is
what keeping today's call sites rules out. The price of inlining is that
`logAt` becomes part of the binary API: changing its signature later breaks
callers until they recompile.

## Stack

- [ ] **`spec-0127-origin`** — `LogLine.origin`, the inline `log*` functions,
      and the top-level resolution through `ClassValue`. `StderrLogger` prints
      `origin`.
      Done when: a call from a class, from a lambda inside it, from its
      companion, from a nested class, from an anonymous `object`, and from a top-level
      function each capture the expected `origin`.
- [ ] **`spec-0127-library-origins`** — the five direct `LogLine(...)` sites
      pass their own class.
      Done when: each site's existing test asserts its `origin`.
- [x] **`spec-0127-slf4j-by-origin`** — `Slf4jLogger` names its SLF4J logger by
      `origin` unless it was given a name. README and cookbook say so, including
      the change for anyone who configured `<logger name="lark">`.
      Done when: a line from `Registry` reaches the logger named `…Registry`, a
      `Slf4jLogger("app")` still writes to `app`, and a line with no origin
      reaches `lark`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should the `ClassValue` hold the SLF4J logger too?** It would save
   `getLogger`'s own cache lookup, but `lark` cannot name an SLF4J type.
   Recommend not doing it unless a profile shows it matters.
2. **Should a top-level function report `RegistryKt`, or the file name without
   the `Kt`?** Recommend `RegistryKt`, the class as the JVM names it, so it
   matches what a stack trace prints.
3. **What about Java callers?** Java does not inline Kotlin functions, so a call
   from Java reports `LoggerKt`. Recommend accepting that: lark's API is
   Kotlin-first.
4. **What about a call inside someone's own inline helper?** It reports the
   class where the helper is used, not where it is defined. Recommend accepting
   that, because it is usually the class people want.

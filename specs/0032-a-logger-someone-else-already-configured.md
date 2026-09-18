# 0032 — A logger someone else already configured

## Problem

`lark` has no logging dependency, and `StderrLogger` is what a service gets
until it binds something else. `docs/cookbook.md` answers the obvious next
question with twenty lines of `Slf4jLogger` and the sentence "the adapter is
yours". That is right about `lark` and wrong about everyone who uses it: the
twenty lines are the same twenty lines every time, and the part people get wrong
is not the level `when`.

It is the annotations. `logAnnotated("correlation_id" to id) { … }` puts a
pair on every line written inside the block *including on forks*, which is
the one thing an MDC cannot do. The cookbook's adapter flattens them onto
the end of the message, so a `%X{correlation_id}` pattern, a JSON encoder, and
every log aggregator's field search all see nothing. The annotation survives
the fork and dies at the backend.

The dependency is usually not a new one either. `petshop` already declares
`logback-classic` as `runtimeOnly` — "so a failure in a handler reaches a
terminal rather than an SLF4J no-op" — and its `main` is `runApp(Petshop)` with
no `logger.locally` around it. So its own `logInfo` goes to `System.err`
through `StderrLogger` while Pekko's lines go through logback, and one service
prints in two formats.

## Not doing

- **No backend.** `slf4j-api` only, the way `lark-otel` takes the OpenTelemetry
  API and leaves the SDK to the service.
- **No dependency on `lark`.** A new module, so `NoOtherDependenciesTest` in
  `lark` says exactly what it says today.
- **No SLF4J provider.** Routing *other* libraries' slf4j output back into
  `lark` means winning a `ServiceLoader` race for the whole JVM. Its own spec,
  if ever.
- **No deleting the cookbook recipe.** It stays, shortened, because the point it
  makes about `lark` is still true.

## Shape

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-slf4j:$larkVersion")
}
```

```kotlin
val exit = logger.locally(Slf4jLogger()) { runApp(app) }
```

- `Slf4jLogger(name: String = "lark")` — one `org.slf4j.Logger`, named, because
  a backend's configuration is by name and a service wants to set the level of
  its own lines.
- Level maps to `debug`/`info`/`warn`/`error`; `cause` goes to the throwable
  overload rather than into the message.
- **Annotations go to the MDC**, put in before the call and put back after,
  so `%X{correlation_id}` and a JSON encoder both find them. Every annotation
  `logAnnotated` bound, and the elapsed pairs `logSpan` contributes through
  `spans.elapsedAt`.
- The message reaches the backend as written, with nothing appended.

## Why this shape

The MDC is the whole reason to ship this rather than paste it. `lark` propagates
a binding across a fork and slf4j's MDC does not, so the adapter is the seam
where one becomes the other, and it is a seam with a wrong answer that looks
right: append to the message and every line still reads correctly to a human
while being unsearchable to everything else.

Putting values in and taking them back out — rather than setting and leaving
them — is what makes that safe on a pooled or virtual thread `lark` did not
open. A thread that logs outside any `logAnnotated` must not inherit the last
block's correlation id.

The alternative is `MDCContextMap`, an slf4j SPI that would let `lark`'s
bindings *be* the MDC for the whole JVM. It is one `ServiceLoader` entry, it
would carry annotations onto third-party lines for free, and it takes over
something the service may already have configured. Recommend the plain adapter.

## Stack

- [ ] **`spec-0032-the-module`** — `lark-slf4j`: the module, `Slf4jLogger`,
      levels, `cause`, and a `NoOtherDependenciesTest` allowing `lark`, Arrow
      and `slf4j-api` and nothing else.
      Done when: each of the four levels reaches the matching slf4j call, an
      error carries its `Throwable` as a throwable, and the classpath test names
      three jars.
- [ ] **`spec-0032-the-mdc`** — annotations into the MDC around each call and
      out again; the cookbook section replaced by the dependency and one line.
      Done when: a line written inside `logAnnotated` has the pair in its MDC, a
      line written after it does not, and a line written on a fork opened inside
      the block does.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Can a level be checked before a line is built?** No: `logInfo` interpolates
   its message and builds a `LogLine` before any `Logger` sees it, so a disabled
   level costs the same as an enabled one. Fixing that is a change to `lark`'s
   own API, not to this adapter. Recommend saying so in the KDoc and leaving it.
2. **`lark-slf4j`, or `lark-app-slf4j`?** It adapts `lark`'s `Logger` and knows
   nothing about a graph. Recommend `lark-slf4j`.
3. **What restores an MDC that was already set?** Recommend saving the map
   before and restoring it after, not clearing: a service that set its own keys
   outside `lark` keeps them.
4. **Does `CookbookTest` still compile the old recipe?** It compiles against
   `lark-app-pekko`'s runtime classpath, which has `slf4j-api` but would not
   have this module. Recommend checking that before writing the second entry —
   if it cannot compile the new one-liner, the docs change belongs elsewhere.

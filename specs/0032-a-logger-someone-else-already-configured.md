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
- **No version anybody has to match.** The facade is declared at the oldest
  version this compiles against, so resolution can only raise it. Declaring a
  2.x floor would drag a service on 1.7 across the change from
  `StaticLoggerBinder` to `ServiceLoader` providers, and its logging would go
  quiet because it added this module.
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

There is no second step. The module registers a `Logger` through a
`ServiceLoader`, and `lark`'s default resolves through one — so a service that
has bound nothing logs where everything else on its classpath already logs.

```kotlin
logger.locally(MyLogger()) { runApp(app) }   // still wins: a test, or your own
```

- `Slf4jLogger(name: String = "lark")` — a backend is configured by name, and a
  service wants a level for its own lines that is not its libraries'.
- Level maps to `debug`/`info`/`warn`/`error`; `cause` goes to the throwable
  overload rather than into the message.
- **Annotations go to the MDC**, put in before the call and put back after, so
  `%X{correlation_id}` and a JSON encoder both find them. Every annotation
  `logAnnotated` bound, and the elapsed pairs `logSpan` contributes through
  `spans.elapsedAt`.
- The message reaches the backend as written, with nothing appended.

## Why this shape

A wrapper around `runApp` is a line to remember, and `petshop` is the evidence
that it gets forgotten. Discovery is the ergonomics `lark-otel` already has —
"put it on the classpath; nothing else" — and it is the difference between an
adapter being a dependency and being a step in a README nobody reaches.

The MDC is the other half. `lark` carries a binding across a fork and slf4j's
MDC does not, so the adapter is the seam where one becomes the other, and it is
a seam with a wrong answer that looks right: append to the message and every
line still reads correctly to a human while being unsearchable to everything
else. Putting values in and taking them back out — rather than setting and
leaving them — is what makes that safe on a pooled or virtual thread `lark` did
not open.

The alternative to discovery is `MDCContextMap`, an slf4j SPI that would let
`lark`'s bindings *be* the MDC for the whole JVM. It would carry annotations
onto third-party lines for free, and it takes over something the service may
already have configured. Recommend the plain adapter.

## Stack

- [ ] **`spec-0032-the-module`** — `lark-slf4j`: the module, `Slf4jLogger`,
      levels, `cause`, and a `NoOtherDependenciesTest` allowing `lark`, Arrow
      and `slf4j-api` and nothing else.
      Done when: each of the four levels reaches the matching slf4j call, an
      error carries its `Throwable` as a throwable, and the classpath test names
      nothing else.
- [ ] **`spec-0032-the-mdc`** — annotations into the MDC around each call and
      out again.
      Done when: a line written inside `logAnnotated` has the pair in its MDC, a
      line written after it does not, a line written on a fork opened inside the
      block does, and a key the service set itself survives.
- [ ] **`spec-0032-found-on-the-classpath`** — `lark`'s default `Logger`
      resolves through a `ServiceLoader`, resolved once; `lark-slf4j` ships the
      service file; README and cookbook say so.
      Done when: with the module on the test classpath and nothing bound,
      `logger.get()` is a `Slf4jLogger`, `lark`'s own tests still get
      `StderrLogger`, and `logger.locally` still wins.

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
4. ~~**Does `CookbookTest` still compile the old recipe?**~~ Answered: it
   compiles only fences marked `<!-- cookbook -->`, so the dependency snippet is
   prose and the hand-rolled adapter below it stays compiled.
5. **Which implementation wins if two are registered?** The first the
   `ServiceLoader` yields, and the order is not specified. Recommend leaving it:
   two logging adapters on one classpath is the same mistake as two backends,
   and a service that means it binds one with `logger.locally`.
6. **What if the facade is not there at all?** A service file naming a class
   that cannot link makes `ServiceLoader` throw while instantiating it, so the
   first log line would take out the caller. Recommend catching that and falling
   back to stderr, having said once why.
7. **Whose classloader?** This class's, not the thread's — which thread a line
   is written on is exactly what `lark` makes vary. A container that isolates
   the application from the library loader would not find the adapter.

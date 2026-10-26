# 0133 — A closeable node closes itself

## Problem

Most nodes in a service's graph hold something open, and almost every one of
them is given back the same way. Petshop's graph has fourteen releases and
eleven only call `close()`: seven on the node itself (the pool, two Kafka
producers, two hubs, the GC binder, the OpenTelemetry SDK) and four on the
`Running` a stream's node wraps. The other three remove a registry, terminate
an actor system and stop a server. surefold's own cookbook writes `singleOf(::Hikari, Hikari::close)`. The release is boilerplate
whenever the type is already `AutoCloseable`, and forgetting it is silent: a
`singleOf(::Pool)` compiles, starts, and leaks the pool on every stop.

## Not doing

- No change to `single` or `singleOf`. A node built without a release keeps
  having none; nothing is closed that was not asked to be.
- No reflection or `instanceof` at release time. Whether a node closes is
  decided by the type it was declared with.
- No `Closeable`-specific path. `java.io.Closeable` is an `AutoCloseable`.
- No ordering change: release stays reverse dependency order.

## Shape

`closingOf`, the `singleOf` family for a type that is `AutoCloseable`, with
`close()` as its release, for zero to nine dependencies:

```kotlin
val database: Module = closingOf(::pool).boundTo<DataSource>()      // pool(settings): HikariDataSource
val events: Module = closingOf<Producer<Long, ShopEvent>> { Kafka.producer(...) }
class Arrivals(running: Running<Nothing, Long>) : AutoCloseable by running
val arrivals: Module = closingOf { streams: StreamBackend -> Arrivals(tick(...).start(streams)) }
```

Each is `singleOf(create) { it.close() }`, so a throw from `close` is reported
as a release failure exactly as one from a hand-written release is.

## Why this shape

A name of its own, not an overload of `singleOf`: Kotlin cannot overload on an
upper bound, and a `singleOf` that closed `AutoCloseable` values on its own
would start closing things in graphs that never asked, a client handed in from
outside among them. `closingOf` reads as what it does at the call site, and a
reviewer sees the release without opening the type. The alternative, a
`.closing()` modifier on a module (`singleOf(::Pool).closing()`), cannot see the
node's type once it is a `Module`; recommend the function.

## Stack

- [ ] **`spec-0133-closing-of`**: `closingOf` for zero to nine dependencies in
      `surefold-app`, the `.api` dump, and tests that a node built by it is closed
      on release, in reverse order, and that a throwing `close` is reported.
      Done when: `./gradlew :surefold-app:build` passes with the new tests.
- [ ] **`spec-0133-docs`**: the cookbook's `singleOf(::Hikari, Hikari::close)`
      and the other `close` releases in `docs/` become `closingOf`, and the
      reference table at the end of the cookbook gains a row.
      Done when: `CookbookTest` compiles every changed recipe.

## Acceptance

```bash
./gradlew :surefold-app:build :surefold-app-pekko:test
```

## Open questions

Answered:

1. **The name** is `closingOf`, so it sorts beside `singleOf`.
2. **A checker warning** for `singleOf(create) { it.close() }` is a later spec,
   once this has shipped.
3. **`install`'s exit** is not given to `close()`; a release that needs it is
   written by hand, as today.

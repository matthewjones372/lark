# 0001 — A handler that raises

## Problem

An Arrow codebase writes its request handlers as `suspend fun … = either { … }`
and runs them on a coroutine dispatcher, so a blocking port inside the body
(JDBC, a client with no async surface) parks a dispatcher thread for the whole
request. The maintainer wants the same `Raise` body on a virtual thread of its
own: block freely, and let a `raise` never cross a thread because the boundary
opens and closes on that one.

## Not doing

- **No coroutines.** The body runs on a virtual thread and blocks.
- **No fork/join** — no `parZip`, `Resource`, `Schedule`. Spec 0002 does the
  forking.
- **No streams.** One unit of work answers with one value or one declared
  failure.

## Shape

```kotlin
val answer: Either<UserNotFound, User> = flock {          // a boundary on this virtual thread
    users.find(id) ?: raise(UserNotFound(id))               // blocking JDBC, on this thread
}
```

- A `Raise<E>` boundary opened on a virtual thread, closed on it, with the
  result handed back as a value: a return is the `Right`, a raise the `Left`,
  a throw rethrows. Arrow's `bind()`, `ensure`, `ensureNotNull` work in the
  body because they are `Raise<E>` extensions.
- One virtual thread per unit of work, and a way to name the executor instead
  for a test that wants a direct one or a service that owns its own.

## Why this shape

A `Raise` boundary and a thread boundary are the same boundary: the block
opens on the virtual thread and closes there, so a raise never crosses a
thread. The alternative — a boundary on the caller's thread and a separate
thread hop — leaves the blocking call where it was by default.

## Stack

- [x] **`spec-0001-scaffold`** ([#1](https://github.com/matthewjones372/lark/pull/1)) —
      Gradle build, the `lark` module on `arrow-core`, `NoOtherDependenciesTest`, README.
      Done when: `./gradlew build` is green and the classpath test lists Arrow
      and nothing else.
- [x] ~~**`spec-0001-rising`**, **`spec-0001-naming`**, **`spec-0001-executor`**~~
      ([#2](https://github.com/matthewjones372/lark/pull/2),
      [#4](https://github.com/matthewjones372/lark/pull/4),
      [#5](https://github.com/matthewjones372/lark/pull/5)) — built as a
      request-handler binder for a web framework and withdrawn by spec 0003:
      lark is a library for Arrow code, not a server. What survives of them is
      the executor knob, as `on:` in spec 0004.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — decided by the maintainer in chat, 2026-09-03:

1. **Toolchain.** 21, the floor for virtual threads; CI runs the build on 21
   and 25. `synchronized` in a JDBC driver pins the carrier before JDK 24
   (JEP 491) — the README says so.
2. **Default executor.** `Thread.ofVirtual().start` per unit of work, so there
   is no global until an executor is named.
3. **A throw in the body** unwinds as a throw: only a declared failure is a
   value.

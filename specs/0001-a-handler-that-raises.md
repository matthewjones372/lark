# 0001 — A handler that raises

## Problem

An Arrow codebase binding a Pelican endpoint writes `handledOrFail { either {
… }.toOutcome() }`: an `either` boundary opened by hand, a fold at the end, and
a body on Pekko's dispatcher — so a blocking call inside it (JDBC, a client with
no async surface) parks a dispatcher thread for the whole request. Pelican's
spec 0036 shipped the conversions and deferred exactly this: "No Raise/effect
integration yet … anything deeper waits for a use to demand it." The use is a
service written in `Raise` whose ports block.

## Not doing

- **Nothing in `pelican-core`.** Lark is its own repository; the binder is the
  `lark-pelican` module, `lark` plus `pelican-arrow` plus the JDK.
- **No coroutines.** The body runs on a virtual thread and blocks.
- **No fork/join** — no `parZip`, `Resource`, `Schedule`. Spec 0002 does the
  forking; `Rising<E>` is designed to become its scope.
- **No streams, no several-successes.** `streamedOrFail` and `handledOneOf`
  stay; a lark answers with one success or one declared failure.
- **No client side.** `Outcome.toEither()` already exists.

## Shape

```kotlin
val routes = listOf(
    getUser handledRaising { id ->
        users.find(id) ?: raise(UserNotFound(id))     // blocking JDBC, on this request's own virtual thread
    },
    getOrder handledRaising { id ->
        val order = orders.find(id).bind()            // Either<OrderError, Order> from the service
        ensure(order.isVisibleTo(caller)) { raise(forbidden(OrderHidden(id))) }
        order
    },
)
```

- `Endpoint<I, Outcome<E, T>>.handledRaising(f: Rising<E>.(I) -> T)`, infix,
  beside Pelican's binders. `Rising<E>` is a `Raise<E>` carrying the request's
  `Params`, so `bind()`, `ensure`, `ensureNotNull` and `setHeader` all work in
  the body without a second receiver.
- `raise(error)` is the single declared failure, as bare `err(error)` is; with
  several declared it is refused where the response is written, by the same
  `failureNamedBy` check.
- `raise(forbidden(error))` is the naming form: the declaration is callable and
  returns `Outcome<E, Nothing>`, so `Rising<E>` overloads `raise` for it.
- One virtual thread per request. The binder returns a `CompletionStage`
  completed from that thread: a return with `ok(value)`, a raise with the
  `Err`, a throw exceptionally — the interpreter's own `catch (t: Throwable)`
  sees what it sees today.
- `handledRaising(on: Executor)` names the executor, for a test that wants a
  direct one or a service that owns its own.

## Why this shape

A `Raise` boundary and a thread boundary are the same boundary: the block
opens on the virtual thread and closes there, so a raise never crosses a
thread, and the interpreter gets a plain stage as from any async binder. The
alternative — a `Raise` binder on the caller's thread and a separate
virtual-thread binder — puts one handler behind two names and leaves the
blocking call on the dispatcher by default. A scope class beats context
parameters because it needs nothing past what `pelican-arrow` compiles against.

## Stack

- [x] **`spec-0001-scaffold`** ([#1](https://github.com/matthewjones372/lark/pull/1)) — Gradle build, the `lark` module on
      `pelican-arrow`, `NoOtherDependenciesTest`, README.
      Done when: `./gradlew build` is green and the classpath test lists core,
      arrow and nothing else.
- [x] **`spec-0001-rising`** ([#2](https://github.com/matthewjones372/lark/pull/2)) — `Rising<E>`, `handledRaising`, the single
      declared failure, one virtual thread per request.
      Done when: `app.call(getUser, 1L)` answers the declared 404 from a
      `raise`, and a test sees `Thread.currentThread().isVirtual` in the body.
- [x] **`spec-0001-naming`** ([#4](https://github.com/matthewjones372/lark/pull/4)) — `raise(declared(error))` with several failures.
      Done when: each declared status is answered, and a bare raise on a
      two-failure endpoint is refused with bare `err`'s message.
- [x] **`spec-0001-executor`** ([#5](https://github.com/matthewjones372/lark/pull/5)) — `on: Executor`; a cancelled stage interrupts
      the thread.
      Done when: a handler on a supplied executor runs there, and a cancelled
      stage ends its thread at the body's next blocking call.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — decided by the maintainer in chat, 2026-09-03:

1. **Which Pelican?** `1.0.0-RC1` from Maven Central, which ships
   `pelican-arrow`, `pelican-test` and `pelican-test-pekko`.
2. **Toolchain.** 21, the floor for virtual threads; CI runs the build on 21
   and 25. `synchronized` in a JDBC driver pins the carrier before JDK 24
   (JEP 491) — the README says so.
3. **Default executor.** `Thread.ofVirtual().start` per request, so there is
   no global until `on:` names an executor.
4. **A throw in the body** unwinds as today: the stage completes
   exceptionally and the interpreter owns the undeclared failure.

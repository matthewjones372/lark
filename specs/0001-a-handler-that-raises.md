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

- **Nothing in `pelican-core`.** Lark is its own repository and a leaf module:
  `pelican-arrow` plus the JDK.
- **No coroutines.** The body runs on a virtual thread and blocks.
- **No fork/join** — no `parZip`, `Resource`, `Schedule`. A second spec, once
  a handler needs to fork.
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

- [ ] **`spec-0001-scaffold`** — Gradle build, the `lark` module on
      `pelican-arrow`, `NoOtherDependenciesTest`, README.
      Done when: `./gradlew build` is green and the classpath test lists core,
      arrow and nothing else.
- [ ] **`spec-0001-rising`** — `Rising<E>`, `handledRaising`, the single
      declared failure, one virtual thread per request.
      Done when: `app.call(getUser, 1L)` answers the declared 404 from a
      `raise`, and a test sees `Thread.currentThread().isVirtual` in the body.
- [ ] **`spec-0001-naming`** — `raise(declared(error))` with several failures.
      Done when: each declared status is answered, and a bare raise on a
      two-failure endpoint is refused with bare `err`'s message.
- [ ] **`spec-0001-executor`** — `on: Executor`; a cancelled stage interrupts
      the thread.
      Done when: a handler on a supplied executor runs there, and a cancelled
      stage ends its thread at the body's next blocking call.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Which Pelican?** `pelican-arrow` is new since 0.2.0 and 1.0.0 is
   unreleased. Recommended: pin the 1.0.0 candidate; `includeBuild` until then.
2. **Toolchain 21 or 25?** 21 is the floor; `synchronized` in a JDBC driver
   pins the carrier before 24 (JEP 491). Recommended: build on 21, document
   the pinning, test on both.
3. **Default executor: a module-level per-task executor, or
   `Thread.ofVirtual().start` per request?** Recommended: `Thread.ofVirtual()`,
   so there is no global until `on:` names one.
4. **A throw in the body: unwind as today, or a 500 naming the thread?**
   Recommended: as today — the interpreter owns undeclared failures.

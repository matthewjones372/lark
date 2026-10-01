# 0110 — Guards that compose

## Problem

Specs 0111–0113 add a circuit breaker, a bulkhead and a rate limiter. Lark already has `retry` and `timeout`. Each
of the five has its own call shape and its own way of refusing. A call guarded by all of them becomes five nested
lambdas, each with its own rejection handler. The order of the nesting matters, and nothing in the types says what
the right order is. Pekko and resilience4j both hit this: resilience4j adds `Decorators` and a fixed aspect order on
top of separate APIs. Lark can have one shape from the start.

## Not doing

- **A fixed order.** Composition is left to right, with the outermost guard first. The cookbook gives the
  recommended order. Nothing enforces it.
- **Fallbacks as a guard.** A fallback is the caller's own `catch`, or `ifRejected` (below).
- **Guards over streams or actors.** A stage or an `ask` is a call inside a guard.

## Shape

```kotlin
/** Wraps a call, and may refuse to run it by throwing a [Rejected]. */
interface Guard {
    fun <E, A> Raise<E>.guard(block: Raise<E>.() -> A): A

    /** [this] outside, [inner] inside. */
    infix fun then(inner: Guard): Guard
}

operator fun <A> Guard.invoke(block: () -> A): A                       // outside Raise
fun <E, A> Raise<E>.guarded(guard: Guard, ifRejected: (Rejected) -> E, block: Raise<E>.() -> A): A

fun retrying(schedule: Schedule<Throwable, *>): Guard                   // over the existing retry
fun timingOut(duration: Duration): Guard                               // over the existing timeout

sealed class Rejected(val guard: String, message: String) : RuntimeException(message) {
    class CircuitOpen(guard: String, val retryAt: Instant) : Rejected(...)    // 0111
    class BulkheadFull(guard: String) : Rejected(...)                         // 0112
    class RateLimited(guard: String, val retryAfter: Duration) : Rejected(...) // 0113
}
```

```kotlin
val partner = retrying(backoff) then breaker then limiter then bulkhead then timingOut(2.seconds)

val quote = partner { http.quote(id) }                                 // throws a Rejected, or the call's own throw

either<QuoteError, Quote> {
    guarded(partner, ifRejected = { QuoteError.Unavailable(it.guard) }) { quote(id) }
}
```

- **One rejection type.** A caller handles every refusal in one `when` over `Rejected`. Because it is sealed, the
  compiler names a new guard's case wherever one is handled.
- `then` is associative. `a then (b then c)` behaves the same as `(a then b) then c`.
- **Inside `Raise`, a raise passes through every guard untouched.** It is never counted, retried or rejected,
  because `nonFatalOrThrow` treats it as control flow, as `retry` already does.
- **Recommended order, written in the cookbook:** retry → breaker → limiter → bulkhead → timeout → call. The breaker
  sees timeouts as failures. A call waiting for a token holds no bulkhead permit. Retry is outermost, so each
  attempt passes the breaker again.
- `retrying` gives every `Rejected` to the schedule like any other throw. A schedule that should not retry an open
  breaker says so with `doWhile`.

## Why this shape

`guard` is an extension on the caller's `Raise`, so `timingOut` forks through `Raise<E>.timeout`, which already
handles a raise across a fork. A `Guard` over `() -> A` would carry a `Raise` onto another thread, which AGENTS.md
forbids. `Rejected` is an exception, so the throwing form needs nothing extra, and `guarded` catches it by name to
turn it into a declared error. The alternative is a fixed-order `Resilience(retry, breaker, limiter, bulkhead,
timeout)` value. It makes the wrong order impossible, but it cannot hold two breakers or a guard written by a
caller. Free composition with a documented order is recommended.

## Depends on

Nothing. 0111–0113 each depend on this one.

## Stack

- [ ] **`spec-0110-guard`** — `Guard`, `then`, `invoke`, `guarded`, `Rejected`, `retrying` and `timingOut`, in
      `lark`.
      Done when: two test guards compose in both groupings with the same trace, a raise passes through
      `retrying` and `timingOut` uncounted, and `guarded` turns a `Rejected` into the declared error.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Free `then` or a fixed-order `Resilience` value?** Recommend free `then`, with the order in the cookbook.
2. **Should `retrying` skip `Rejected.CircuitOpen` by default?** Recommend no. Retrying a schedule into an open
   breaker is cheap, and the `retryAt` time is there for a schedule that wants to wait for it.
3. **Is `Rejected` an exception, or a value returned beside `A`?** Recommend an exception that `guarded` turns into
   a value, which is the same split as `timeout` and `timeoutOrNull`.

# 0110 — A breaker that stops calling

## Problem

Lark has `Schedule`, `retry`, `timeout` and `raceN`, which cover one call that fails. Nothing covers a dependency
that is down: every handler still calls it, waits out its timeout and retries, so a dead database costs each request
its full timeout and multiplies the load on whatever is trying to recover. Pekko and Akka give this a `CircuitBreaker`.
A Lark service today writes its own failure counter in an `AtomicReference`, or goes without one.

## Not doing

- **Bulkheads and rate limiters.** These are the other Pekko-style patterns. They are separate specs if wanted
  (see Open questions), not stack entries here.
- **A breaker shared across nodes.** Each process has its own breaker, as in Pekko. A cluster-wide breaker is a
  replicated-state problem.
- **Stream or actor operators.** A stage or an `ask` is wrapped in `protect` by the caller. Nothing in
  `lark-stream` or `lark-actor` changes.
- **Callbacks on transitions.** State is read and measured, not pushed to listeners.

## Shape

```kotlin
val db = CircuitBreaker(
    name = "accounts-db",
    maxFailures = 5,
    resetAfter = Schedule.exponential<Unit>(1.seconds).jittered(0.8, 1.2) zipLeft Schedule.recurs(6) // the wait before each half-open trial
)

val row = db.protect { jdbc.query(id) }                 // throws CircuitOpenException while open

either<AccountError, Row> {
    db.protect(ifOpen = { AccountError.Unavailable(it.retryAt) }) { lookup(id) }  // open is a raise, not a throw
}

db.state // Closed(failures = 2) | Open(until: Instant) | HalfOpen
```

- **Closed** counts consecutive failures. A success resets the count. At `maxFailures` the breaker opens.
- **Open** rejects every call at once, without running it, until the next delay from `resetAfter` has passed.
- **HalfOpen** lets exactly one trial call through, and rejects the rest. If the trial succeeds the breaker closes
  and `resetAfter` starts again from its first step. If it fails the breaker reopens on the schedule's next step.
  When the schedule is `Done`, it keeps waiting on its last delay.
- A **failure** is a throw (non-fatal, as `retry` decides it). A `raise` counts only when `countsAsFailure(e)` says
  so, which is false by default.
- Time is read from `clock.get()`, so `TestClock` drives every transition in tests.
- Metrics: `lark.breaker.state` (gauge: 0 closed, 1 half-open, 2 open) and `lark.breaker.calls` (counter tagged
  `outcome=success|failure|rejected`), both tagged with `name`.

## Why this shape

Making `resetAfter` a `Schedule` reuses what Lark already has for backoff and jitter. This gives Pekko's
`withExponentialBackoff` and `withRandomFactor` without adding parameters. The breaker is a value held in a
`single`, with one `AtomicReference<State>` and no thread of its own: an open breaker expires when the next call
reads the clock, not on a timer. An open circuit is something a caller should plan for, so inside `Raise` it becomes
the caller's own error through `ifOpen`. Outside `Raise` it throws, as `timeout` does. The other design is
`protect` returning `Either<CircuitOpen, A>`. That forces a second error type into every call site and is not
recommended. There is no `callTimeout` parameter: `db.protect { timeout(2.seconds) { … } }` already composes
from existing parts.

## Depends on

Nothing.

## Stack

- [ ] **`spec-0110-breaker`** — `CircuitBreaker`, `State`, the throwing `protect` and `CircuitOpenException`, in
      `lark`.
      Done when: on a `TestClock`, the breaker goes closed → open → half-open → closed and half-open → open, with
      exactly one trial let through under concurrent callers on virtual threads.
- [ ] **`spec-0110-breaker-raise`** — `Raise<E>.protect` with `ifOpen` and `countsAsFailure`.
      Done when: a raise passes through uncounted by default, counts when told to, and an open breaker raises
      `ifOpen`'s error.
- [ ] **`spec-0110-breaker-metrics`** — the gauge and counter, plus a cookbook section "Stop calling something
      that is down".
      Done when: `capturingMetrics` reads back each state and outcome by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Bulkhead and rate limiter too?** Drafted as specs 0111 and 0112. They are independent of this one.
2. **Consecutive failures or a failure rate over a window?** Pekko counts consecutive failures; resilience4j
   uses a sliding window. Recommend consecutive failures now. A window can be a later `CircuitBreaker` constructor.
3. **Should a raise count as a failure by default?** Recommend no. A declared failure is an answer, not an outage.
4. **Where does it live?** Recommend `lark` itself, next to `Retry.kt`. It needs only the JDK and `Clock`.
   A `lark-resilience` module would hold three small files.

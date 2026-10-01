# 0111 — A breaker that stops calling

## Problem

Lark has `Schedule`, `retry`, `timeout` and `raceN`, which cover one call that fails. Nothing covers a dependency
that is down: every handler still calls it, waits out its timeout and retries, so a dead database costs each request
its full timeout and multiplies the load on whatever is trying to recover. Pekko and Akka give this a `CircuitBreaker`.
A Lark service today writes its own failure counter in an `AtomicReference`, or goes without one.

## Not doing

- **A breaker shared across nodes.** Each process has its own breaker, as in Pekko.
- **A failure-rate window.** The breaker counts consecutive failures (see Open questions).
- **Callbacks on transitions.** State is read and measured, not pushed to listeners.
- **Its own call shape.** It is called through a `Policy` (spec 0110).

## Shape

```kotlin
val breaker = CircuitBreaker(
    name = "accounts-db",
    maxFailures = 5,
    resetAfter = Schedule.exponential<Unit>(1.seconds).jittered(0.8, 1.2) zipLeft Schedule.recurs(6),
)

val db = policy("accounts-db") { guard(breaker); attemptTimeout(2.seconds) }
val row = db { jdbc.query(id) }                        // throws Rejected.CircuitOpen while open

breaker.state // Closed(failures = 2) | Open(until: Instant) | HalfOpen
```

- The runner calls `admit()`, which answers `retryAt` while the breaker is open, and `record(outcome)`. These are
  `internal` to `lark`, and the open case surfaces as `Rejected.CircuitOpen(name, retryAt)`.
- **Closed** counts consecutive failures. A success resets the count. At `maxFailures` the breaker opens.
- **Open** rejects every call at once, without running it, until the next delay from `resetAfter` has passed.
- **HalfOpen** lets exactly one trial call through, and rejects the rest. If the trial succeeds the breaker closes
  and `resetAfter` starts again from its first step. If it fails the breaker reopens on the schedule's next step.
  When the schedule is `Done`, it keeps waiting on its last delay.
- A **failure** is a non-fatal throw that `countsAsFailure(t)` accepts. By default that is every throw except a
  `Rejected` from a guard inside this one, which is a refusal, not an outage. A `raise` never counts (spec 0110).
- Time is read from `clock.get()`, so `TestClock` drives every transition in tests.
- Metrics: `lark.breaker.state` (gauge: 0 closed, 1 half-open, 2 open) and `lark.breaker.calls` (counter tagged
  `outcome=success|failure|rejected`), both tagged with `name`.

## Why this shape

Making `resetAfter` a `Schedule` reuses what Lark already has for backoff and jitter. This gives Pekko's
`withExponentialBackoff` and `withRandomFactor` without adding parameters. The breaker is a value held in a
`single`, with one `AtomicReference<State>` and no thread of its own: an open breaker expires when the next call
reads the clock, not on a timer. There is no `callTimeout` parameter. An `attemptTimeout` inside the breaker in the policy
gives the same effect, and the breaker counts the `TimeoutException` as a failure.

## Depends on

0110, for `Policy` and `Rejected`.

## Stack

- [ ] **`spec-0111-breaker`** — `CircuitBreaker`, `State` and `countsAsFailure`, its `Step`, and its runner row, in
      `lark`.
      Done when: on a `TestClock`, the breaker goes closed → open → half-open → closed and half-open → open, with
      exactly one trial let through under concurrent callers on virtual threads, an inner `Rejected` is not
      counted, and a policy waits for half-open only when `retryAt` falls within its deadline.
- [ ] **`spec-0111-breaker-metrics`** — the gauge and counter, plus a cookbook section "Stop calling something
      that is down".
      Done when: `capturingMetrics` reads back each state and outcome by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Consecutive failures or a failure rate over a window?** Pekko counts consecutive failures; resilience4j
   uses a sliding window. Recommend consecutive failures now. A window can be a later constructor.
2. **Should a `raise` ever count as a failure?** Recommend no, as spec 0110 says. A declared failure is an answer,
   not an outage. A caller who disagrees throws instead.
3. **Where does it live?** Recommend `lark` itself, next to `Retry.kt`. It needs only the JDK and `Clock`.

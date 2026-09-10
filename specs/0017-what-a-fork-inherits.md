# 0017 — What a fork inherits

## Problem

A fork inherits nothing. `Thread.ofVirtual().start(command)` hands the body a
thread with no memory of the one that opened it, and every combinator here
forks that way.

Time is the first thing that costs. `Schedule` waits with `Thread.sleep`
([Schedule.kt:285](../lark/src/main/kotlin/io/github/matthewjones372/lark/Schedule.kt)),
so a test of five exponential retries waits five of them, and `lark-app`'s
`probe` has no `retry` for that reason. Everything a request carries is the
second: a branch of a `parMap` cannot be told an id or a tenant without a
parameter through every function between. ZIO answers both with `FiberRef`,
which a child fiber inherits; there is no equivalent here.

## Not doing

- **No logging.** The log a fork carries is spec 0018, on this one's first
  entry.
- **No `ScopedValue`.** It is preview on the toolchain this builds against.
- **No change to `lark-app`.** `probe`'s retry follows this, in its own spec.
- **No virtual-time scheduler.** The test clock moves when a test moves it.

## Shape

A binding a fork inherits, which is the mechanism; a clock is its first user.

```kotlin
class LarkLocal<A> internal constructor(private val initial: () -> A) {
    fun get(): A
    fun <B> locally(value: A, block: () -> B): B
}

fun <A> larkLocal(initial: () -> A): LarkLocal<A>
```

```kotlin
val requestId = larkLocal { "none" }

requestId.locally("abc-123") {
    parMap(rows) { row -> requestId.get() }     // every branch reads abc-123
}
```

The capture wraps the `Runnable` where a fork is opened rather than inside
`VirtualThreads`, so a fork on Pekko's dispatcher inherits the same way.

```kotlin
interface Clock {
    fun now(): Instant
    fun sleep(duration: Duration)               // interruptible
}

val clock: LarkLocal<Clock> = larkLocal { SystemClock }
```

```kotlin
clock.locally(fixed(t0)) { backoff.retry { flaky.row(id) } }     // returns in microseconds

clock.locally(moving) {                                          // TestClock, for asserting on when
    flock {
        val f = async { poller.run() }
        moving.adjustWhenBlocked(60.minutes)                     // waits for a sleeper, then moves
        f.await()
    }
}
```

## Why this shape

A binding rather than a `LarkContext` of named fields: lark's own first user is
a clock and the second is a request id belonging to the service, not here. One
capture point serves both, and spec 0018 adds a third without touching a fork
site again. The alternative — a `Clock` parameter on every combinator, the way
`on = pool` is one — is explicit and answers nothing a service carries.

`adjustWhenBlocked` is what ZIO gets free and this cannot. Its runtime knows a
fiber is suspended; nothing here knows a fork has reached its `sleep`, so a
plain `adjust` races the fork it means to release.

## Stack

- [ ] **`spec-0017-context`** — `LarkLocal`, `larkLocal`, `locally`, and the
      capture and rebind at every fork site.
      Done when: a value bound outside `parMap` is readable in every branch, and
      a branch does not see a binding made after it forked.
- [ ] **`spec-0017-clock`** — `Clock`, `SystemClock`, `fixed`, and `Schedule`
      waiting through the bound clock.
      Done when: five exponential retries under `fixed` finish in under a
      millisecond, with the schedule's decisions unchanged.
- [ ] **`spec-0017-testclock`** — `TestClock`, `adjust`, `setTime`,
      `adjustWhenBlocked`.
      Done when: nothing has happened before sixty minutes and exactly one thing
      has after.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`ThreadLocal` now, or `ScopedValue` behind a toolchain bump?** Recommend
   `ThreadLocal`: 21 is the floor, lark owns every fork site, and it is a swap
   behind the same API later.
2. **Does `timeout` measure on the bound clock too?** Recommend yes, or a test
   still waits real seconds for a timeout it meant to skip. It is the larger
   change of the two, since `timeout` races rather than sleeps — so its own
   entry if it does not fit `spec-0017-clock`.
3. **Is `LarkLocal` public?** Recommend yes: a service carrying a request id
   through a `parMap` has the same problem, and with no coroutine context to
   reach for this is the answer. A call with nothing bound gets the initial
   value, so nothing existing changes.

# 0145: one actor lifecycle under Cell and TestActor

## Problem

An actor's lifecycle is written twice: once in `Cell` (`Runtime.kt:438-1056`)
and again in `TestActor` (`TestActor.kt:290-701`). `settled`, `timed`, `heard`,
`letGo`, `supervised`, `failed`, `started`, stash replay and the end sequence
appear in both, near line for line. The only real difference is where state
lives: plain fields in `Cell`, and an immutable `Run` swapped through an
`AtomicReference` in `TestActor` so a test thread can read it.

Every lifecycle change is therefore made twice. Runtime.kt has 37 commits since
August and TestActor.kt has 22. One drift is already confirmed: a `tell` under
test actors does not carry trace context (`Carried`, spec 0122), so a handler's
`Carriers` are empty in tests but not in production. `ParityTest` checks only 3
scheduling scenarios, so nothing would catch the next drift.

## Not doing

- Changing `Behaviour`, `Ctx`, `TestActors` or `TestActor`'s public interface.
- Batched `steps` (spec 0086). That stays in `Cell`, which decides how many
  messages a turn runs. `TestActor` keeps its one-step-per-message turns.
- The mailbox, room, runners, asks, children and watchers. These stay with each
  adapter.
- The `@PlumbingSeam` casts in `Entities.kt` (`as? DeadLetters`). Those come
  later, if the port below makes them unnecessary.

## Shape

```kotlin
// lark-actor, internal
internal class Lifecycle<M : Any, S, E>(
    private val behaviour: Behaviour<M, S, E>,
    private val host: Host<M, E>,
    restart: Schedule<Failure<E>, *>?,
    stashCapacity: Int,
) {
    val state: S
    fun started(): Next<S>
    fun stepped(ctx: Ctx<M>, message: M): Next<S>
    fun signalled(ctx: Ctx<M>, signal: Signal): Next<S>
    fun timed(ctx: Ctx<M>, key: Any, message: Any): Next<S>
    fun finish()                       // children, stash, Stopping, end
}

internal interface Host<M, E> {
    fun schedule(key: Any, delay: Duration, message: Any, every: Duration?)
    fun cancel(key: Any)
    fun sleep(delay: Duration)
    fun dead(message: Any, why: DeadLetter.Why)
    fun stopChildren()
    fun restarted(delay: Duration)     // Cell: a metric. TestActor: `delays`.
    fun failedFor(failure: Failure<E>) // TestActor records `failure`.
}
```

`Cell` and `TestActor` each implement `Host` and hold one `Lifecycle`. A throw
with no schedule goes on to the caller in both today
(`Runtime.kt:1000`, `TestActor.kt:630`), so `Lifecycle` needs no policy switch
for it.

## Why this shape

Composition, with the adapter as the `Host` port, keeps scheduling out of the
lifecycle. Each adapter keeps its own clock, and the lifecycle never learns
which one it has. The alternative is an abstract base class both extend. That
saves one indirection but shares fields across the seam, and `TestActor`'s
cross-thread state does not fit a base class written for one thread. The
recommendation is composition, provided the actor benchmarks agree.

## Stack

- [ ] **`spec-0145-timers`**: `Lifecycle` takes timers by key, scoped timers
      (`settled`), receive timeout and stash, all lifted out of `Cell`.
      Done when: lark-actor tests pass and Cell no longer has `settled`,
      `heard` or `drop`.
- [ ] **`spec-0145-supervision`**: start, supervision, restart and the end
      sequence move into `Lifecycle`.
      Done when: lark-actor tests pass and actor benchmarks stay within noise.
- [ ] **`spec-0145-test-actor`**: `TestActor` runs on `Lifecycle`. A failing
      test comes first: a carrier set on `tell` is visible in the handler under
      test actors.
      Done when: that test passes and `TestActor.kt` drops its copies.
- [ ] **`spec-0145-parity`**: `ParityTest` keeps the scheduling cases only.
      Lifecycle cases are tested through `TestActor`.
      Done when: no lifecycle rule is asserted in both suites.

## Acceptance

```bash
./gradlew :lark-actor:test :lark-cluster:test
./gradlew :lark-actor-benchmarks:jmh   # compared with main, same machine
```

## Open questions

1. Is the trace-context gap deliberate, because tests never install carriers?
   I recommend treating it as a bug. Spec 0122's otel tests would then work
   under test actors too.
2. `TestActor.state` is read from the test thread. Should `Lifecycle` publish
   state through a `@Volatile` field, or should `TestActor` keep its `Run`
   snapshot and copy from `Lifecycle` after each turn? I recommend the
   `@Volatile` field: it is one write per turn, and `Cell` pays the same.
3. Does entry 1 stay under 200 lines? It is mostly moved code. If not, I
   recommend splitting off receive timeout and stash into an entry of its own.

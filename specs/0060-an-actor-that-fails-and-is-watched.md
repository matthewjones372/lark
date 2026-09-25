# 0060 — An actor that fails and is watched

## Problem

A `lark-actor` step that throws stops its actor, and nothing else happens:
there is no restart, no way for another actor to hear that it stopped, and no
way to raise a declared failure. 0059 put all three off to here. An actor
cannot spawn children either, so an actor that owns workers has to be handed
them from outside and cannot outlive or restart them. And closing a flock
waits on a step that is blocked forever, because 0059 chose not to interrupt.

## Not doing

- **Timers, receive timeout, stash** (0061). **Routers, receptionist, dead
  letters** (0062).
- **Watching across nodes.** `watch` answers `Stopped`; `Unreachable` stays for
  0065.
- **Supervision strategies beyond one actor.** No one-for-all, no escalation
  chain past the parent.

## Shape

```kotlin
sealed interface Signal { data object Stopping; data class Terminated(val ref: ActorRef<*>) }

val shop = behaviour<Shop, Shelf, ShopError>(Shelf.empty) { ctx, shelf, message ->
    when (message) {
        is Restock -> become(shelf + repo.load(message.id).bind())   // a Left raises ShopError
        is Hire -> { ctx.watch(ctx.spawn("clerk", clerk())); stay() }
    }
}.onSignal { ctx, shelf, signal -> when (signal) { Stopping -> stay(); is Signal.Terminated -> stay() } }

flock {
    val ref = spawn("shop", shop, restart = Schedule.exponential(100.milliseconds) and Schedule.recurs(5))
    val gone: Deferred<Stopped> = watch(ref)
}
```

- **Declared failures.** `Behaviour<M, S, E>`; the step runs in `Raise<E>`.
  `behaviour(...)` without a failure type infers `E = Nothing`, so a call site
  is unchanged; a type written out gains `, Nothing`, since Kotlin cannot give
  a class a default type argument or two arities under one name. A
  `raise` and a throw both fail the actor, and supervision sees which.
- **Supervision.** `restart: Schedule<Failure<E>, *>` on `spawn`, default
  none. A failure asks the schedule; a step restarts from `initial` after its
  delay, on the flock's `Clock`, and the schedule ending stops the actor. The
  mailbox survives a restart, so nothing told to it is lost. `Failure<E>` is
  `Raised(e)` or `Thrown(t)`, and one schedule sees both: a caller who wants
  them apart writes `doWhile` on the case. A restart is in place, with the same
  ref and address, so holders of the ref need nothing.
- **Signals.** A sealed `Signal`, handled beside messages with no `else`:
  `Stopping` before the actor ends, after a throw too, since that is when
  giving a resource back matters most; `Terminated(ref)` for an actor it
  watches. A behaviour with no `onSignal` ignores them.
- **Watch.** `ctx.watch(ref)` delivers `Terminated`; `Flock.watch(ref)` from
  outside answers a `Deferred`. An `ask` pending on an actor that stops still
  answers `Stopped`, as in 0059.
- **Children.** `ctx.spawn` spawns into the actor's own nested scope. A child
  stops before its parent. A parent's failure stops its children, as Pekko's
  default does, and a restarted parent spawns them again in its step.
- **Close interrupts, where it is safe.** On virtual threads, closing a flock
  interrupts a step still running, so a step blocked forever cannot hold the
  flock open. On any other executor it still waits, since an interrupt could
  land on the pool's next task.
- **`.test()`.** Every piece above runs synchronously, `TestClock` drives
  restart delays, and `restarts`, `signals` and `children` are readable.

## Why this shape

Supervision on `Schedule` rather than a strategy type: lark already has one
way to say "retry, with backoff, this many times", and a restart is that. The
alternative is Pekko's `SupervisorStrategy`, a second vocabulary for the same
decision. Restart from `initial` rather than keeping state: a failure may have
been caused by the state, and keeping it is `resume`, which a schedule can
express later by answering "continue with the state" if anyone asks for it.

## Stack

- [x] **`spec-0060-raise`** ([#117](https://github.com/matthewjones372/lark/pull/117)) — `Behaviour<M, S, E>`, `Raise<E>` in the step,
      `Failure<E>`. Done when: 0059's tests pass with no change but `, Nothing`
      on a written-out `Behaviour` type, and a `raise` stops the actor.
- [x] **`spec-0060-restart`** ([#118](https://github.com/matthewjones372/lark/pull/118)) — `restart` on `spawn`, on the flock's `Clock`.
      Done when: a failing actor restarts on the schedule under `TestClock`, and
      its mailbox is intact.
- [x] **`spec-0060-watch`** ([#119](https://github.com/matthewjones372/lark/pull/119)) — `Signal`, `onSignal`, `ctx.watch` and
      `Flock.watch`. Done when: a watcher hears `Terminated` exactly once, on
      both runtimes.
- [x] **`spec-0060-children`** ([#120](https://github.com/matthewjones372/lark/pull/120)) — `ctx.spawn`, stop and restart order. Done when:
      a child's `Stopping` comes before its parent's.
- [ ] **`spec-0060-interrupt`** — close interrupts a running step on virtual
      threads. Done when: a flock holding a step blocked forever closes.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Nothing. All four were answered as recommended and are in Shape: a restart is
in place, one schedule sees raised and thrown failures, a parent's failure
stops its children, and `Stopping` follows a throw.

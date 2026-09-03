# 0002 — A handler that forks

## Problem

A handler on a virtual thread can block, but it cannot yet do two things at
once. The pair that Arrow gives a coroutine — `async`/`await` and
`parZip`/`parMap`/`raceN` from `arrow-fx-coroutines`, with a `raise` in any
branch ending the rest — has no spelling here, so a handler that needs a user
and their orders fetches them one after the other. Spec 0001 deferred exactly
this. The maintainer wants it (chat, 2026-09-03): "the awaits etc you get with
coroutines and arrow, but with virtual threads".

## Not doing

- **No coroutines and no `StructuredTaskScope`.** The former is a dependency;
  the latter is still a preview API on 21 and 25. `Thread.ofVirtual()` and a
  join are what the JDK offers unflagged, and they are enough.
- **No `Resource`, no `Schedule`, no timeouts.** Each is its own spec.
- **No forking across a `Raise` boundary.** A fork opens its own; only values
  cross back.
- **Nothing Pelican in `lark`.** The scope lives in `lark`; `lark-pelican`
  only makes the handler scope one.

## Shape

```kotlin
getDashboard handledRaising { id ->
    val user = async { users.find(id) ?: raise(UserNotFound(id)) }   // on its own virtual thread
    val orders = async { orders.forUser(id).bind() }                  // and so is this
    Dashboard(user.await(), orders.await())                           // a raise in either surfaces here
}

val totals: Either<PricingError, List<Total>> = flock {               // outside a handler: the same scope
    parMap(baskets) { price(it).bind() }
}

val (user, orders) = parZip({ users.find(id) ?: raise(UserNotFound(id)) }, { orders.forUser(id).bind() }) { u, o -> u to o }
val quote = raceN({ fast.quote(id) }, { slow.quote(id) })
```

- `Flock<E> : Raise<E>` in `lark`: a scope that owns every thread forked in it.
  `flock<E, A>(block: Flock<E>.() -> A): Either<E, A>` opens one on the
  calling thread and closes it when the block leaves — by return, raise or
  throw — joining every fork still running after interrupting it.
- `Flock<E>.async(block: Flock<E>.() -> T): Deferred<T>` forks a virtual
  thread with a nested `Flock<E>` on it. `Deferred<T>.await(): T` joins and
  re-raises or re-throws in the caller. A `Deferred` never awaited is still
  joined at scope close.
- `parZip` (arity 2 to 4), `parMap(iterable) { }` and `raceN` (2 and 3): the
  arrow-fx-coroutines names and shapes, over `async`. The first branch to
  raise or throw interrupts its siblings; `raceN` interrupts the losers.
- `Rising<E>` from spec 0001 becomes a `Flock<E>`, so every handler has it.

## Why this shape

Coroutine names for coroutine semantics: a reader who knows `async`/`await`
and `parZip` should not learn a second vocabulary for the same guarantees.
There is no clash because kotlinx.coroutines is not on the classpath. The
alternative — exposing a `Thread`/`Future` pair and letting the handler join
by hand — leaves a raise on a fork as an exception on the wrong thread, which
is the bug the scope exists to make impossible. Interrupt is the only
cancellation the JDK has, so cancellation means: the body ends at its next
interruptible blocking call, and a scope closes only when its forks have.

## Stack

- [x] **`spec-0002-flock`** ([#3](https://github.com/matthewjones372/lark/pull/3)) — `Flock<E>`, `flock { }`, `async`/`await`,
      scope close joins and interrupts.
      Done when: a raise in a fork surfaces at `await()` as the block's `Left`,
      and after the block returns no forked thread is alive.
- [x] **`spec-0002-par`** (`main`, 1fe019e) — `parZip` and `parMap`; the first failure
      interrupts the siblings.
      Done when: a branch that raises returns before its sibling's sleep would
      have ended, and the sibling was interrupted.
- [x] **`spec-0002-race`** (`main`, 1fe019e) — `raceN`; the losers interrupted.
      Done when: the winner's value is the result and the losers are
      interrupted before they finish.
- [ ] **`spec-0002-rising`** — `Rising<E> : Flock<E>` in `lark-pelican`.
      Done when: the dashboard handler above answers through
      `api.inMemory()`, and a raise in a fork answers the declared status.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — decided by the maintainer in chat, 2026-09-03:

1. **A fork that ignores interrupt** is joined until it ends: a scope that
   returns with a thread still running is a leak with a nicer name. The README
   says which JDBC calls honour interrupt.
2. **Exceptions in a fork** re-throw at `await()` as the same instance, no
   wrapping; the interpreter answers as spec 0001 says.
3. **Two modules.** `lark` (arrow-core and the JDK) holds `Flock`;
   `lark-pelican` holds `Rising` and the binder. Each asserts its classpath.

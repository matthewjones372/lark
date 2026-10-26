# 0206 — Values changed together

## Problem

Surefold has no way to change several pieces of shared state as one step. A service that moves stock between two
warehouses, or takes a seat and a payment slot together, reaches for a `ReentrantLock` and has to pick a lock order
by hand. Two locks taken in the wrong order deadlock, and nothing in the type says which lock guards which field.
Waiting until the state allows a step ("until there is stock") means a `Condition` and a loop around it.

`CircuitBreaker` and `RateLimiter` use one `AtomicReference` each, which only works for state that fits in one value.
Arrow's STM fits the rest, but it is built on `suspend`, and `suspend` can't appear in `surefold`.

## Not doing

- **No wrapping or porting of `arrow-fx-stm`.** The design, names and engine are Surefold's own.
- **No collections yet.** A queue, map or semaphore built on these values is a later spec.
- **No side-effect checks.** A body can run more than once. The docs say so, and nothing enforces it.
- **No durability.** This is memory shared between forks, not a journal.

## Shape

```kotlin
val stock = Shared(10)
val reserved = Shared(0)

// One step: either both writes land or neither does.
fun reserve(n: Int) = transact {
    val left = stock.get()
    if (left < n) waitForChange()   // parks the virtual thread until stock or reserved is written
    stock.set(left - n)
    reserved.update { it + n }
}

// The first branch that does not wait wins.
val from = transact { firstOf({ takeFrom(east) }, { takeFrom(west) }) }

// A declared failure undoes the step and answers Left, as anywhere else.
val r: Either<OutOfStock, Unit> = attempt { transact { if (stock.get() == 0) fail(OutOfStock) else stock.update { it - 1 } } }
```

- `Shared<T>` is a value forks share. `get`, `set` and `update` are only callable inside `transact { }`, because they
  take the `Transaction` receiver. `Shared.peek()` reads the latest committed value without a transaction.
- `transact` runs on the calling thread and returns the block's value once it has committed. It retries on its own
  when a value it read was changed by someone else's commit before its own.
- `waitForChange()` parks until a value the body has read is committed again, then runs the body again.
  Parking is interruptible, so `cancel()`, a lost race and `timeout { }` stop a waiting `transact`.
- A `fail` or any throw from the body throws away the step's writes and unwinds as it would anywhere else.
- A `transact` inside a `transact` joins the outer one.

## Why this shape

The names are Surefold's. `retry` already means `Retry.kt`'s schedules, so the blocking call is `waitForChange`.
`Shared`/`transact` say what the user means. Arrow's `TVar`/`atomically` describe how it works.
**Alternative:** use Arrow's names so Arrow users recognise them. Recommended: Surefold's names, since 0206 doesn't
behave like Arrow's (it blocks instead of suspending).

The first engine commits under one global lock: read versions are checked and writes published while holding it.
That is easy to show correct. **Alternative:** per-value version locks taken in id order (TL2), which is faster when
commits don't overlap but harder to get right. Recommended: the global lock now, and TL2 in its own spec once
`surefold-stm-benchmarks` shows the global lock is the bottleneck.

## Stack

- [ ] **`spec-0206-shared`** — `Shared`, `Transaction`, `transact`, `get`/`set`/`update`/`peek`, global-lock commit.
      Done when: 1,000 virtual threads making transfers between ten `Shared` balances leave the total unchanged, and
      a body that throws commits nothing.
- [ ] **`spec-0206-wait`** — `waitForChange`, `firstOf`, parking and waking on what the body read.
      Done when: a waiting `transact` wakes on a commit to a value it read, never on one it didn't, and
      `deferred.cancel()` ends it with no writes.
- [ ] **`spec-0206-fail-docs`** — `fail` inside `transact`, nested `transact`, and a "Values changed together"
      section in `docs/core.md` with a compiled `<!-- core-transact -->` fence and a GLOSSARY entry.
      Done when: `CoreGuideTest` compiles the fence and `attempt { transact { fail(e) } }` is `Left(e)` with nothing
      written.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Module.** Put this in `surefold` itself (stdlib only, so it's allowed), or in a new `core/surefold-stm`?
   Recommended: `surefold`. It's no heavier than `CircuitBreaker`, and forks are where it gets used.
2. **Names.** `Shared`/`transact`/`waitForChange`/`firstOf`, or something else? Recommended: as sketched. These
   are the cheapest thing to change in this draft.
3. **Should `peek()` exist?** It is handy for metrics and logs, and a trap if used to decide a write.
   Recommended: keep it, with KDoc saying it reads outside any step.
4. **Should a `transact` that retries too often say so?** Recommended: a `transact.retries` counter through
   `Metrics`, and no limit.

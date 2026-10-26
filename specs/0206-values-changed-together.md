# 0206 — Values changed together

## Problem

Surefold has no way to change several pieces of shared state as one step. A service that moves stock between two
warehouses, or holds seats in several sections for one order, reaches for a `ReentrantLock` and has to pick a lock
order by hand. Two locks taken in the wrong order deadlock, and nothing in the type says which lock guards which
field. Waiting until the state allows a step ("until there is stock") means a `Condition` and a loop around it.

`CircuitBreaker` and `RateLimiter` use one `AtomicReference` each, which only works for state that fits in one value.
Arrow's STM fits the rest, but it is built on `suspend`, and `suspend` can't appear in `surefold`.

## Not doing

- **No wrapping or porting of `arrow-fx-stm`.** The design, names and engine are Surefold's own.
- **No locks in the commit.** A thread interrupted or descheduled halfway through a commit holds nobody up.
- **No collections yet.** A queue, map or semaphore built on these values is a later spec.
- **No effect checks, after-commit effects or conflict reporting.** Those are specs 0207, 0208 and 0209.
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
- `transact` runs on the calling thread and returns the block's value once it has committed. It runs the block
  again on its own when a value it read was changed by someone else's commit first.
- **A step never sees a half-committed state.** Every read is checked against the version clock, so a body that is
  doomed to retry can't loop or throw on values that never existed together.
- `waitForChange()` parks until a value the body has read is committed again, then runs the body again.
  Parking is interruptible, so `cancel()`, a lost race and `timeout { }` stop a waiting `transact`.
- A `fail` or any throw from the body throws away the step's writes and unwinds as it would anywhere else.
- A `transact` inside a `transact` joins the outer one.

## Why this shape

**Engine.** Each `Shared` holds an `AtomicReference` to its value and the version that wrote it, and a global
`AtomicLong` clock orders commits. A commit swaps every value it writes in one multi-word CAS (Guerraoui, Kogan,
Marathe and Zablotchi, 2020): it installs a description of the change into each value, in `Shared` id order, then
marks it done. A step that finds another commit half-done finishes it before going on. That makes the commit
lock-free. Steps that touch different values never contend, read-only steps write nothing, and each `Shared`
keeps its waiters in a lock-free stack that its writers unpark.

**Alternatives.** A global commit lock is easier to show correct, but its holder can be interrupted mid-commit
while everyone queues behind it. A single `AtomicReference` holding an immutable snapshot of every value is
lock-free and simple, but every commit contends on one reference, and a `Shared` that is no longer used stays
in the snapshot forever. Recommended: the per-value multi-word CAS.

The names are Surefold's. `retry` already means `Retry.kt`'s schedules, so the blocking call is `waitForChange`.

## Stack

- [ ] **`spec-0206-mcas`** — the multi-word CAS on its own, `internal`, with Lincheck tests.
      Done when: Lincheck finds no non-linearizable history for 2–4 threads swapping 1–3 overlapping words.
- [ ] **`spec-0206-shared`** — `Shared`, `Transaction`, `transact`, `get`/`set`/`update`/`peek`, version-checked
      reads. Done when: 1,000 virtual threads making transfers between ten `Shared` balances leave the total
      unchanged, a body that throws commits nothing, and a body never sees two balances from different commits.
- [ ] **`spec-0206-wait`** — `waitForChange`, `firstOf`, and the waiter stacks.
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
2. **Names.** `Shared`/`transact`/`waitForChange`/`firstOf`, or something else? Recommended: as sketched.
3. **Lincheck as a test dependency of `surefold`.** It never reaches the runtime classpath, so
   `NoOtherDependenciesTest` stays green, but it is a new library. Recommended: yes. A lock-free engine shouldn't
   ship tested only by "the total came out right".
4. **Starvation.** A long step that keeps losing to short commits could retry forever. Should it fall back to a
   slow path that always wins, giving up strict lock-freedom? Recommended: no fallback. Spec 0209 makes the
   retries visible.
5. **Should `peek()` exist?** It is handy for metrics and logs, and a trap if used to decide a write.
   Recommended: keep it, with KDoc saying it reads outside any step. Spec 0207 flags it inside `transact`.

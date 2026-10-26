# 0209 — Values changed together

## Problem

Surefold has no way to change several pieces of shared state as one step.

**The use case.** A box office sells seats in several sections of one venue. Many buyers check out at once, each on
their own fork. One order holds seats in the stalls *and* the circle, and it gets either all of them or none. When a
section is short, the order waits until another buyer releases seats, unless its deadline passes first. Seats must
never be sold twice, and a cancelled or timed-out order must never leave seats half-held.

Today that service reaches for a `ReentrantLock` per section and has to pick a lock order by hand. Two locks taken
in the wrong order deadlock, and nothing in the type says which lock guards which field. Waiting until the state
allows a step ("until seats are free") means a `Condition` and a loop around it.

`CircuitBreaker` and `RateLimiter` use one `AtomicReference` each, which only works for state that fits in one value.
Arrow's STM fits the rest, but it is built on `suspend`, and `suspend` can't appear in `surefold`.

## Not doing

- **No wrapping or porting of `arrow-fx-stm`.** The design, names and engine are Surefold's own.
- **No locks in the commit.** A thread interrupted or descheduled halfway through a commit holds nobody up.
- **No collections yet.** A queue, map or semaphore built on these values is a later spec.
- **No effect checks, after-commit effects or conflict reporting.** Those are specs 0210, 0211 and 0212.
- **No durability.** This is memory shared between forks, not a journal.

## Shape

The box office, written with this spec:

```kotlin
data class SoldOut(val section: String)

class BoxOffice(sections: Map<String, Int>) {
    private val free: Map<String, Shared<Int>> = sections.mapValues { Shared(it.value) }
    private val held = Shared(emptyMap<String, Map<String, Int>>())   // order id -> seats per section

    // Every section the order wants, or none. Waits while any section is short.
    fun hold(orderId: String, wanted: Map<String, Int>) = transact {
        wanted.forEach { (section, n) ->
            val left = free.getValue(section).get()
            if (left < n) waitForChange()             // parks; a release in any section it read wakes it
            free.getValue(section).set(left - n)
        }
        held.update { it + (orderId to wanted) }
    }

    // Gives the seats back, which wakes any order waiting on those sections.
    fun release(orderId: String) = transact {
        val seats = held.get()[orderId] ?: return@transact
        seats.forEach { (section, n) -> free.getValue(section).update { it + n } }
        held.update { it - orderId }
    }

    // A closed section is a declared failure: the step writes nothing and answers Left.
    fun Fail<SoldOut>.holdOpen(orderId: String, wanted: Map<String, Int>, closed: Shared<Set<String>>) = transact {
        wanted.keys.firstOrNull { it in closed.get() }?.let { fail(SoldOut(it)) }
        hold(orderId, wanted)                         // joins this step
    }

    fun seatsLeft(section: String): Int = free.getValue(section).peek()   // for metrics and logs
}

val office = BoxOffice(mapOf("stalls" to 120, "circle" to 60))

// Many buyers at once. However they interleave, no seat is sold twice.
parMap(orders) { office.hold(it.id, it.seats) }

// Wait up to two seconds. On timeout the step is interrupted and nothing is held.
val held: Unit? = timeoutOrNull(2.seconds) { office.hold("o-42", mapOf("stalls" to 2, "circle" to 1)) }

// Take a seat from whichever of two box offices can give one now, preferring the first.
transact { firstOf({ east.hold("o-43", one) }, { west.hold("o-43", one) }) }
```

- `Shared<T>` is a value forks share. `get`, `set` and `update` are only callable inside `transact { }`, because they
  take the `Transaction` receiver. `Shared.peek()` reads the latest committed value without a transaction.
- `transact` runs on the calling thread and returns the block's value once it has committed. It runs the block
  again on its own when a value it read was changed by someone else's commit first.
- **A step never sees a half-committed state.** Every read is checked against the commit count, so a body that is
  doomed to retry can't loop or throw on values that never existed together.
- `waitForChange()` parks until a value the body has read is committed again, then runs the body again.
  Parking is interruptible, so `cancel()`, a lost race and `timeout { }` stop a waiting `transact`.
- A `fail` or any throw from the body throws away the step's writes and unwinds as it would anywhere else.
- A `transact` inside a `transact` joins the outer one.

## Why this shape

**Engine.** Each `Shared` holds an `AtomicReference` to its value and the version that wrote it. A commit swaps
every value it writes in one multi-word CAS (Guerraoui, Kogan, Marathe and Zablotchi, 2020): it installs a
description of the change into each value, in `Shared` id order, then marks it done. A step that finds another
commit half-done finishes it before going on. That makes the commit lock-free. A global commit count is one more
word in every swap, so a commit's writes and its version land together, and values a step only read are swapped for
themselves, so the commit fails if any changed. Read-only steps write nothing. Steps that touch different values
still meet on the commit count, but the loser only retries the swap, never its body. Each `Shared` keeps its waiters
in a lock-free stack that its writers unpark.

(Changed while building `spec-0206-shared`. The draft had a separate `AtomicLong` clock, bumped outside the swap.
A reader could then see one value from a commit and another from before it, both at or under its clock.)

**Alternatives.** A global commit lock is easier to show correct, but its holder can be interrupted mid-commit
while everyone queues behind it. A single `AtomicReference` holding an immutable snapshot of every value is
lock-free and simple, but every commit contends on one reference, and a `Shared` that is no longer used stays
in the snapshot forever. Recommended: the per-value multi-word CAS.

The names are Surefold's. `retry` already means `Retry.kt`'s schedules, so the blocking call is `waitForChange`.

## Stack

Drafted as 0206 and renumbered when main took 0206–0208 for the testkit. The branches keep the number they were
opened under.

- [ ] **`spec-0206-mcas`** — the multi-word CAS on its own, `internal`, with Lincheck tests.
      Done when: Lincheck finds no non-linearizable history for 2–4 threads swapping 1–3 overlapping words.
- [ ] **`spec-0206-shared`** — `Shared`, `Transaction`, `transact`, `get`/`set`/`update`/`peek`, version-checked
      reads. Done when: 1,000 virtual threads making transfers between ten `Shared` balances leave the total
      unchanged, a body that throws commits nothing, and a body never sees two balances from different commits.
- [ ] **`spec-0206-wait`** — `waitForChange`, `firstOf`, and the waiter stacks.
      Done when: a waiting `transact` wakes on a commit to a value it read, never on one it didn't, and
      `deferred.cancel()` ends it with no writes.
- [ ] **`spec-0206-fail-docs`** — `fail` inside `transact`, nested `transact`, and a "Values changed together"
      section in `docs/core.md` built on the box office, with a compiled `<!-- core-transact -->` fence and a
      GLOSSARY entry.
      Done when: `CoreGuideTest` compiles the fence and `attempt { transact { fail(e) } }` is `Left(e)` with nothing
      written.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26). Each answer is the question's recommendation.

1. **Module.** Put this in `surefold` itself (stdlib only, so it's allowed), or in a new `core/surefold-stm`?
   Recommended: `surefold`. It's no heavier than `CircuitBreaker`, and forks are where it gets used.
2. **Names.** `Shared`/`transact`/`waitForChange`/`firstOf`, or something else? Recommended: as sketched.
3. **Lincheck as a test dependency of `surefold`.** It never reaches the runtime classpath, so
   `NoOtherDependenciesTest` stays green, but it is a new library. Recommended: yes. A lock-free engine shouldn't
   ship tested only by "the total came out right".
4. **Starvation.** A long step that keeps losing to short commits could retry forever. Should it fall back to a
   slow path that always wins, giving up strict lock-freedom? Recommended: no fallback. Spec 0212 makes the
   retries visible.
5. **Should `peek()` exist?** It is handy for metrics and logs, and a trap if used to decide a write.
   Recommended: keep it, with KDoc saying it reads outside any step. Spec 0210 flags it inside `transact`.

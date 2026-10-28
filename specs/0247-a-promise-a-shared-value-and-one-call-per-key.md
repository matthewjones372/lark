# 0247 — A promise, a shared value, and one call per key

## Problem

Service-shaped code in Surefold still reaches for `java.util.concurrent` in three places where Surefold has no
primitive of its own:

- **A value handed over once.** `BankNode` and the bank's `Main` pass a started part across with
  `CompletableFuture<Parts>()`, and signal shutdown with `CountDownLatch(1)`. So does `surefold-app`'s `Run.kt`.
  `Deferred` is a fork's own answer, so it cannot be completed by someone else. Waiting on these ignores the bound
  clock, so a test of a startup that never completes hangs instead of failing.
- **One value, changed in place.** `Members` and `Sse` in the bank guard a collection with a `ReentrantLock`, and
  `surefold-app-openfeature` holds a value in an `AtomicReference`. `Shared` and `transact` (0209) already do this
  for any number of values, but a single change is written as `transact { shared.update(f) }`, which nobody reaches
  for.
- **One call per key.** `KeyCache` (0235) hand-rolls "one load per key, every other caller waits" with a map of
  locks. Spec 0246's cache needs the same, and so does a service stopping a stampede on one expensive call.

## Not doing

- No wrapper over `java.util.concurrent` as a whole. Surefold's own engines (the actor runtime, the stream
  engines, Kafka commits, the metrics registries) keep their raw primitives: they are the hot paths that implement
  everything above them.
- No new `Ref` type. `Shared` is the shared value; this spec only makes one change to it short to write.
- No change to `Deferred`. It stays a fork's answer.

## Shape

```kotlin
// A value handed over once, by whoever has it.
val started = Promise<StartError, Parts>()
started.succeed(parts)                       // or started.fail(StartError.PortTaken); a second completion is false
started.await()                              // Either<StartError, Parts>; parks on a virtual thread
started.await(within = 10.seconds)           // Either<…>, or null past 10 seconds on the bound clock

val stopped = Promise<Nothing, Unit>()       // a latch is a promise of Unit
stopped.succeed(Unit)

// One value, changed in place, outside any transaction.
val members = Shared(emptySet<Member>(), name = "bank.members")
members.update { it + joined }
val removed = members.modify { it - left to (left in it) }   // change it and answer something, atomically
members.awaitUntil { it.size >= 3 }          // parks until a change makes it true, as waitForChange does

// One call per key, however many ask at once.
val flights = SingleFlight<String, PriceError, Price>()
flights.run("sku-1") { loadPrice("sku-1") }  // the first caller runs it; the rest wait and get the same Either
```

- **`Promise`** is a `Shared` holding no answer yet, and `await` is `transact { get() ?: waitForChange() }`, so it
  costs no new machinery and wakes on the same commit as any other change. `succeed` and `fail` answer whether they
  completed it. `await` is cancelled with the scope it is called in. `within` reads the bound clock, and answers null
  once it passes, as `timeoutOrNull` does.
- **`Shared.update`**, **`modify`** and **`awaitUntil`** are one-value transactions with the same retry and
  `name` metric as `transact`, so a `Shared` changed both ways stays consistent.
- **`SingleFlight.run(key)`** runs the block once per key while it is in flight. Callers arriving meanwhile wait on
  its `Promise` and are answered its `Either`. A caller cancelled while waiting stops waiting; the block keeps
  running for the others. A block that throws completes every waiter with the throw, rethrown in each. Once it ends,
  the key is free, and nothing is remembered: remembering is 0246's cache.

## Why this shape

All three sit on 0209's `Shared` and `transact`, so they inherit its correctness, its retry metric, and its wake on
commit, and they are a few dozen lines each rather than three new locking schemes. A promise built on a transaction
also composes with one: `transact { started.peek() ?: other.get() }` waits on either.

**Alternative:** thin classes over `CompletableFuture`, `AtomicReference` and a lock map. Recommended against: they
would ignore the bound clock and the scope's cancellation, which are the two things a test needs, and add a second
model of shared state beside `transact`.

## Stack

- [x] **`spec-0247-shared`** ([#873](https://github.com/matthewjones372/surefold/pull/873)): `Shared.update`, `modify` and `awaitUntil`.
      Done when:
      - a thousand forks each `update { it + 1 }` and the value is a thousand;
      - `awaitUntil` wakes on the change that makes it true, and not before.
- [x] **`spec-0247-promise`** ([#876](https://github.com/matthewjones372/surefold/pull/876)): `Promise`, `succeed`, `fail`, `await`, `await(within)`.
      Done when:
      - twenty waiters all get the value;
      - a second `succeed` answers false and changes nothing;
      - on a `TestClock`, `await(within = 10.seconds)` answers null once the clock passes 10 seconds;
      - a waiter's scope cancelled mid-wait stops it.
- [x] **`spec-0247-single-flight`** ([#879](https://github.com/matthewjones372/surefold/pull/879)): `SingleFlight`.
      Done when:
      - twenty forks calling `run("k")` at once run the block once and all get its `Either`;
      - a cancelled waiter does not stop the block;
      - a throw reaches every waiter;
      - the next `run("k")` after it ends runs again.
- [ ] **`spec-0247-uses`**: the bank example and `surefold-app`'s `Run.kt` on `Promise` and `Shared`.
      Done when: none of `BankNode`, `Main`, `Members`, `Sse` or `Run.kt` imports `CountDownLatch`,
      `CompletableFuture` or `ReentrantLock`, and their tests pass unchanged.
- [ ] **`spec-0247-app`**: `surefold-app`'s `Start.kt`, `surefold-app-actor`'s `Actors.kt` and
      `surefold-app-openfeature`'s holder on `Promise` and `Shared`.
      Done when: none of them imports `CompletableFuture`, `ReentrantLock` or `AtomicReference`, and their tests pass
      unchanged.

## Acceptance

```bash
./gradlew :surefold:build :surefold-bank:build :surefold-app:build
```

## Open questions

1. **Does `await` answer `Either`, or throw?** Recommended: `Either<E, A>`, with `Nothing` as the error of a promise
   that cannot fail, so `await()` reads as the value there.
2. **Should `SingleFlight` live in core, or in 0246's `surefold-cache`?** Recommended: core. It is useful without a
   cache, as a stampede guard, and the cache then depends on it rather than owning it.
3. **Should `Promise` time out by default?** Recommended: no. An `await` with no `within` waits for as long as its
   scope lives, as `Deferred.await` does. A test that wants a bound says so.
4. **Should `KeyCache` move onto `SingleFlight` here?** Recommended: no, in 0246's last entry, where it moves onto
   the whole cache in one change.

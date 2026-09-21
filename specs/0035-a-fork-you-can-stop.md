# 0035 — A fork you can stop

## Problem

A fork ends when its combinator says so. The first branch to fail interrupts
its siblings, `raceN` interrupts the losers, and `close` interrupts whatever is
left at scope exit
([Flock.kt:114](../lark/src/main/kotlin/io/github/matthewjones372/lark/Flock.kt)).
A handler that works out for itself that one particular fork is no longer worth
waiting for has no move: `Deferred` is `await()` and nothing else, and its KDoc
says why — "not a `Future`, because there is nothing to cancel by hand"
([Deferred.kt](../lark/src/main/kotlin/io/github/matthewjones372/lark/Deferred.kt)).
What a caller does instead is hold the `Thread` and interrupt it, which is a
raise on a fork landing as an exception on the wrong thread — the bug spec
[0002](0002-a-handler-that-forks.md) built `Flock` to make impossible.

The machinery is already written and already correct under a lock:
`Fork.interrupt()` sets `cancelled`, interrupts the borrowed thread, and makes a
fork whose turn on the executor has not come start its body interrupted. It is
`internal` for want of a contract, not for want of an implementation.

## Not doing

- **No `start = Lazy` and no `Deferred.start()`.** Open question 4.
- **No cancel across a scope boundary.** A `Deferred` is cancelled from the
  thread that opened it, inside the block that owns it. "No fork outlives the
  block that opened it" is the invariant, and this does not touch it.
- **No change to the combinators.** `parZip`, `parMap` and `raceN` go on
  cancelling their own forks; nothing here is reachable from them.
- **No change to what `close` surfaces.** A fork nobody cancelled and nobody
  awaited still answers the scope with the interrupt it was sent; `AwaitTest`
  and `AwaitExitTest` assert that, and it stays asserted.
- **No cooperative cancellation.** Interrupt is what the JDK has, a body that
  ignores it is still joined, and lark says so out loud already.

## Shape

```kotlin
either {
    flock {
        val report = async { reports.build(id) }
        val budget = async { billing.check(tenant).bind() }

        if (budget.await().hasCredit) render(report.await())
        else { report.cancel(); Empty }   // returns once that fork has ended
    }
}
```

- `Deferred<T>.cancel()`: interrupts this fork and returns once it has ended.
- A fork that was cancelled and never awaited does not fail its scope.
- A body that swallowed the interrupt and returned anyway still has its value,
  and a later `await()` answers with it: `cancel()` is a request, not a verdict.

## Why this shape

**`cancel()` joins.** Spec 0002 settled that a scope returning with a thread
still running is a leak with a nicer name, and `close` is written to honour it.
A cancel that returned the instant the interrupt was sent would put that promise
one fork out of reach for as long as the block ran on. The alternative is
Java's shape — `Future.cancel(true)` returns at once and you join separately —
which is two calls to get one guarantee, and the guarantee is the reason this is
in lark rather than in the caller.

**A cancel nobody awaits must not fail the scope, and today it would.**
`Fork.unnoticedFailure()` reads `settled()` unfiltered, where `ownFailure()`
drops an interrupt a combinator sent (`Flock.kt:261`, `Flock.kt:274`), so a fork
that let an interrupt leave its body answers the scope with it:
`flock { async { Thread.sleep(60_000) }; "returned" }` throws
`InterruptedException` rather than `Right("returned")`. That is deliberate and
asserted twice — `AwaitTest.kt:53` and `AwaitExitTest.kt:84` both wrap exactly
this shape in `shouldThrow<InterruptedException>` — so it is a contract, not a
hole, and this spec does not touch it. `cancel()` still has to answer for it,
because cancel-and-don't-await is the most natural call the API invites.
Recommended: `cancel()` marks the fork noticed, since cancelling it *is* taking
notice of its outcome. Nothing at close changes, both those tests stand, and the
blast radius stays inside the new method. The alternative — make the caller
`await()` a fork it just cancelled to keep the scope quiet — is a trap nobody
will remember.

## Stack

- [ ] **`spec-0035-cancel`** — `Deferred.cancel()`, marking the fork noticed, its
      KDoc, and the README paragraph on what a cancelled fork answers.
      Done when: a cancelled fork is interrupted and dead before `cancel()`
      returns, a scope whose only fork was cancelled and never awaited answers
      with the block's value, a sibling runs to completion untouched, and
      `AwaitTest` and `AwaitExitTest` pass unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What does `await()` after `cancel()` answer, for a body that did not
   swallow the interrupt?** Today it rethrows the `InterruptedException`.
   Recommend keeping that: lark's story is that interrupt is the only
   cancellation there is, and a `CancellationException` of lark's own is a
   second vocabulary for one event. The alternative is worth a sentence of
   argument, not a paragraph.
2. **Is `cancel()` idempotent, and what does it do to a fork that already
   ended?** Recommend both are no-ops that return at once, so a cancel in a
   `finally` needs no guard around it. A cancel arriving after the fork answered
   still marks it noticed, or the `finally` reintroduces the problem it solves.
3. **Does `cancel()` belong on `Deferred` or on `Flock`?** Recommend
   `Deferred`, so the handle carries the whole lifecycle and `Flock` keeps the
   two members it has.
4. **Does `start = Lazy` follow in a spec of its own?** Recommend not yet. It
   buys only skipping work nobody asked for, an `if` at the use site usually
   buys the same, and `await()` on an eager fork already memoises, so laziness
   adds a second meaning for scope close in exchange for little.

# 0036 — A fork that waits to be asked

## Problem

`async` starts its fork where it is written: `Fork`'s `init` calls `on.execute`
at construction
([Flock.kt:206](../lark/src/main/kotlin/io/github/matthewjones372/lark/Flock.kt)).
So a handler pays for every fork it opens, including the ones a later branch
turns out not to want, and there is no spelling for "fork this only if I ask" —
`Deferred` is `await()` and `cancel()`.

[0035](0035-a-fork-you-can-stop.md) recommended against adding one (open
question 4): an `if` at the use site usually buys the same thing, and `await()`
on an eager fork already memoises, so the outcome is stored and a second
`await()` re-runs nothing. The maintainer asked for it anyway (chat,
2026-09-21). What the `if` does not cover — and what this spec is for — is a
value declared in one place and reached from several branches, where calling at
the use site means duplicating the call. `by lazy { }` is the usual answer and
is wrong inside a `Raise` scope: a raise unwinds through the delegate as an
exception, so Kotlin leaves it uninitialised and the next access re-runs the
lookup, and it runs on the calling thread, so it gets no executor and no
`LarkLocal` inheritance.

## Not doing

- **No `LazyFlock`, and no laziness on a scope.** A scope where nothing starts
  until it is awaited turns `val a = async { }; val b = async { }; a.await() +
  b.await()` into sequential code, which is the one thing `flock` exists to
  stop. The flag goes on the call, as `CoroutineStart` puts it.
- **No change to an eager fork.** `async` without `start` behaves exactly as it
  does today, `Fork` included.
- **No `Deferred` that outlives its scope.** Unchanged from 0035: it is started,
  awaited and cancelled by the thread that opened it.
- **No `Schedule`, `Resource` or combinator taking a `start`.** `parZip` and its
  neighbours fan out on purpose; laziness there is a contradiction.

## Shape

```kotlin
flock {
    val reconciled = async(start = Lazy) { legacy.reconcile(id).bind() }
    val fresh = cache.get(id)
    if (fresh != null) fresh else reconciled.await()   // never forked on a cache hit
}
```

- `Start`: `Eager`, the default, and `Lazy`.
- `Flock<E>.async(on: Executor = this.on, start: Start = Eager, block: …)`.
- `Deferred<T>.start()`: forks it now and returns, idempotent. `await()` starts
  it first if nothing else has.
- A lazy fork that never started is dropped when the scope closes, rather than
  interrupted and joined.

## Why this shape

**The close is the load-bearing part, not the laziness.** `Nest.close`
(`Flock.kt:114`) interrupts then joins every fork, and `join` waits on a
`CountDownLatch(1)` (`Flock.kt:200`) that only the task body counts down. A fork
whose body never ran never counts it down, so a lazy fork nobody started would
hang its scope forever. `close` has to tell "never started" from "running", and
that distinction is the whole change — the rest is a parameter and a method.

**A never-started fork has no outcome, and two readers assume one.**
`settled()` is a `checkNotNull` (`Flock.kt:284`), reached from `unnoticedFailure`
(`Flock.kt:282`) at every close. Both need an answer for a fork that never ran;
recommended: `unnoticedFailure` answers null, because a fork nobody started
cannot have failed. The consequence is that
`flock { async(start = Lazy) { raise(E) }; 42 }` is `Right(42)` where the eager
form is `Left(E)` — correct, and the kind of thing a reader trips over once, so
it belongs in the KDoc.

The alternative shape is a separate `lazyAsync`, leaving `async`'s signature
alone. Recommended against: `CoroutineStart` is the vocabulary being matched,
and a second factory means a third when a third mode arrives.

## Stack

- [x] **`spec-0036-lazy-start`** ([#84](https://github.com/matthewjones372/lark/pull/84)) — `Start`, the `async` parameter,
      and `close` dropping a fork that never ran. `Deferred.start()` left out:
      open question 1 was answered as `Lazy` plus `await` alone.
      Done when: a lazy fork nobody awaits never runs and the scope still
      returns, `await()` on one runs it and answers, a second `await()` re-runs
      nothing, an eager fork is unchanged, and
      `FlockTest`, `AwaitTest` and `AwaitExitTest` pass unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What is the case in hand?** The Problem above argues from the shape of the
   API rather than from a handler that wanted this, because the drafting agent
   has not seen one. A real one belongs there instead, and would settle whether
   `start()` is wanted at all or only `Lazy` plus `await()`.
2. **What does `cancel()` do to a fork that never started?** Recommended: marks
   it noticed and stops it ever starting, with no interrupt and no join — the
   one case where cancelling is exact rather than a request, and worth saying in
   the KDoc for that reason.
3. **Does `start()` on a cancelled fork throw, or do nothing?** Recommended:
   nothing, matching `cancel()`'s own idempotence from 0035.
4. **Is `Lazy` worth its second meaning for scope close?** 0035 said no and this
   spec exists because the maintainer said yes; the question is left open here
   so the answer is recorded next to the code rather than in a chat log.

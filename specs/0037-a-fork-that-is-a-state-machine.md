# 0037 — A fork that is a state machine

## Problem

`Fork` ([Flock.kt:176](../lark/src/main/kotlin/io/github/matthewjones372/lark/Flock.kt))
is a lazy future spread across `started`, `cancelled`, `cutShort`, `outcome`,
`borrowed` and `ownThread`, guarded by a `ReentrantLock` and a `CountDownLatch`.
Six fields give sixty-four combinations, most of them impossible, and each spec
since 0035 has added a branch saying which ones: "cancelled before it was
started" (0035) and "never started, so no latch will count down" (0036). The
next change to add a branch has to reason about all of them again.

It also has no answer for a bounded executor. A Pekko dispatcher or a fixed pool
whose every thread is awaiting a fork still in that pool's queue deadlocks,
because `await` only ever waits.

The floor is JDK 21 ([README](../README.md), "Virtual threads are why the floor
is JDK 21"), so a blocking call inside `synchronized` still ties up its carrier
thread for anyone on 21. CI already runs 25 beside it.

## Not doing

- **No `CompletableFuture`, `CompletionStage` or `Future`** inside `lark`. The
  Pekko bridge in `lark-pekko` stays as it is.
- **No `StructuredTaskScope`.** Still a preview API in 25 (JEP 505), and `Flock`
  already is one, with typed errors.
- **No `ScopedValue` for `LarkLocal`.** `attach` is how OpenTelemetry's
  `ContextStorage` binds, and a scoped value only binds for a block. Open
  question 3.
- **No change to `Deferred`, `Flock` or any combinator's signature.**

## Shape

Nothing new at a call site. Inside, one reference and one `sealed` state:

```
Unstarted ─start─▶ Submitted ─claim─▶ Running(thread) ─body ends─▶ Done(outcome)
    │                  │                    │
  cancel             cancel               cancel
    ▼                  ▼                    ▼
 Done(cancelled)   Done(cancelled)    Interrupting ─sent─▶ Interrupted ─body ends─▶ Done
```

- Every transition is a compare-and-set on one `AtomicReference<State>`.
- Waiting threads are a stack carried inside the state; `LockSupport` parks and
  unparks them. The lock and the latch go.
- The executor gets a ticket, not the body: whoever claims `Submitted` runs it,
  so `await` on a queued fork runs it on the awaiting thread.
- `finish` cannot reach `Done` while `Interrupting`, so a cancel never lands on
  the executor's next task — the guard the lock gives today, as a state.
- The toolchain, the README badge and the release workflow move to 25.

## Why this shape

Starting from nothing on the JVM today, you'd keep the Loom answer — a thread is
the unit of work, interrupt is cancellation, a scope owns its forks — and give
each fork one explicit state rather than flags. Every JDK handle bundles a
completion state machine with a public way to complete it from outside
(`complete`, `obtrudeValue`). Here only the body's own thread writes `Done`.

The alternative is to keep the lock and replace the flags with one `var state`
under it. It is smaller, but it keeps the latch, so a thread helping a queued
fork has nothing to wait on. Recommended: the compare-and-set version.

## Stack

- [ ] **`spec-0037-jdk25`** — toolchain 25, CI matrix `25` only, badge and README.
      Done when: `./gradlew build` is green on 25, and the README says why 25.
- [ ] **`spec-0037-states`** — `Fork` on `State`, same behaviour.
      Done when: every test in `lark` passes unedited, and no `ReentrantLock` or
      `CountDownLatch` is left in `Flock.kt`.
- [ ] **`spec-0037-help`** — `await` claims and runs a fork still `Submitted`.
      Done when: on a one-thread executor, a fork awaiting a fork queued behind
      it answers instead of hanging, and an interrupt never outlives its fork.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Raise the floor for everyone, or publish for 21 and 25?** Recommended:
   raise it. Two bytecode targets would be two builds to keep green for one
   `synchronized` fix a 21 user can also get by upgrading.
2. **Should `Flight` wait on the waiter stack instead of its `Semaphore`?**
   Recommended: not here. It changes when `raceN` notices a winner, and it
   belongs with `select` in 0038.
3. **`LarkLocal` on `ScopedValue`, dropping `attach`?** Recommended: no, while
   the OpenTelemetry integration needs `attach`.
4. **Does running a fork on the awaiting thread break what 0004 promised about
   threads a Pekko dispatcher owns?** The awaiting thread is on that dispatcher
   too, so recommended: no, but it needs a test.

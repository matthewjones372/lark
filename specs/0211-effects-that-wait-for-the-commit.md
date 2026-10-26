# 0211 — Effects that wait for the commit

## Problem

A step that changes state usually has to tell someone: an actor that the seats are held, a log line, a metric. With
spec 0209 there is nowhere to put that. Inside `transact` it runs once per retry, and 0210 refuses it. After
`transact` returns, the caller has to carry the values out and remember to do it, on every path. Anything that
`transact` returned early or failed on must skip it.

ScalaSTM has commit hooks. Nothing ties them to typed actors, or keeps the effect and the change that caused it
in one place.

## Not doing

- **Not durable.** An effect runs after the commit in the same process. A crash between the two loses it. Durable
  "state and message together" is the journal's job.
- **No streams yet.** `Hub.publish` can refuse when the hub is full, and a refusal after commit can't be undone.
  Publishing inside a step needs the hub's room to be part of the step. That is a later spec.
- **No effects before commit.** Nothing runs while the step can still retry.

## Shape

```kotlin
fun hold(orderId: String, n: Int) = transact {
    val left = free.get()
    if (left < n) waitForChange()
    free.set(left - n)
    afterCommit { log.info("held $n for $orderId") }
    tellAfterCommit(orders, Held(orderId, n))     // surefold-actor: afterCommit { orders.tell(…) }
}
```

- **`afterCommit { }`** queues an effect on the step. On commit, the queued effects run on the committing thread,
  in the order they were queued, before `transact` returns. So the caller sees them done.
- **A retry, a `fail`, a throw or an interrupt drops the queue.** An effect runs once per commit, never per run.
- **Nested `transact`** queues on the outermost step, and its effects run when that step commits.
- **An effect that throws** doesn't undo the commit. The remaining effects still run, then `transact` rethrows the
  first throwable, with the rest added as suppressed.
- **`tellAfterCommit(ref, message)`** in `surefold-actor` is `afterCommit { ref.tell(message) }`. It is named so
  that 0210's diagnostic for `tell` can suggest it.
- An effect may call `transact` itself. It starts a new step.

## Why this shape

Effects run synchronously on the committing thread. **Alternative:** hand them to a fork, so `transact` returns
sooner. But then the caller can't tell when they have run, and a test can't assert on them without waiting.
Recommended: synchronous. A caller who wants them in the background puts `async` inside the `afterCommit`.

## Stack

- [x] **`spec-0211-after-commit`** ([#735](https://github.com/matthewjones372/surefold/pull/735)) — `afterCommit`, the queue on `Transaction`, nested steps, throwing effects.
      Done when: a body that retries 50 times under contention runs its effect exactly once, and a body that
      fails runs none.
- [x] **`spec-0211-actors`** ([#739](https://github.com/matthewjones372/surefold/pull/739)) — `tellAfterCommit` in `surefold-actor`, the docs section, and the GLOSSARY entry.
      Done when: a `TestActor` receives one message per committed step across 1,000 contending threads, and the
      `<!-- core-after-commit -->` fence compiles.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26). Each answer is the question's recommendation.

1. **Name.** `afterCommit`, or something more Surefold-like, such as `onceCommitted`? Recommended: `afterCommit`.
   It says when.
2. **An `afterRetry` hook** for code that wants to watch retries? Recommended: no. Spec 0212 reports retries
   without the user writing code.
3. **Run effects under the commit's interrupt status?** If the thread is interrupted after commit, should effects
   still run? Recommended: yes. The state has already changed, and skipping them would leave the world
   inconsistent with it.

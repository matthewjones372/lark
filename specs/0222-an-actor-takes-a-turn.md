# 0222 — An actor takes a turn

## Problem

"Step" means one run of `transact { }` (spec 0209, GLOSSARY.md). The actors
guide now calls one message handled by an actor a **turn**, but the code still
says `step`: `Behaviour.step`, the `step` parameter of `behaviour(…)` and of the
`Behaviour` constructor, and `steps` on `batching(…)`. Someone reading the guide
and then the code meets two words for one thing. A `transact` inside an actor's
handler is a step inside a step.

## Not doing

- **`Schedule`, `Policy` and `Retry`'s `step`.** That is a schedule's next
  decision, not an actor's. Untouched.
- **The test scheduler's step count.** `settle()` fails "after 100,000 steps".
  That counts deliveries and timers, not turns.
- **Cluster's own `Step` messages** in `Cluster.kt` and `Sharding.kt`. They are
  a protocol's, and private.
- **Any behaviour change.** A rename only.

## Shape

```kotlin
class Behaviour<M : Any, S, out E> internal constructor(
    val initial: S,
    val turn: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
    val signal: (Fail<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>)?,
    val start: (Fail<E>.(ctx: Ctx<M>, state: S) -> Next<S>)?,
    internal val turns: (Fail<E>.(ctx: Ctx<M>, state: S, messages: Run<M>) -> Batched<S>)?,
    internal val batch: Int,
) {
    @Deprecated("Named turn, as the actors guide calls it", ReplaceWith("turn"))
    val step: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S> get() = turn
}

fun <M : Any, S> behaviour(initial: S, turn: Fail<Nothing>.(Ctx<M>, S, M) -> Next<S>): Behaviour<M, S, Nothing>

fun <M : Any, S> batching(initial: S, batch: Int, turns: Fail<Nothing>.(Ctx<M>, S, Run<M>) -> Next<S>): Behaviour<M, S, Nothing>

// A wrapper reads the new name.
behaviour.turn(this, ctx, state, message)
```

KDoc in `surefold-actor` says "turn" wherever it means one message handled.
Internal names follow: `Cell.step(message)` becomes `turn`, `StepsSuite`
becomes `TurnsSuite`. The bank example's `Repeatable.kt` moves to `turn`.

## Why this shape

The deprecated getter keeps every caller that reads `behaviour.step` compiling,
with a warning and a one-click fix, as `configured` in `surefold-app-typesafe`
did. **Alternative:** rename with no getter and let callers break. That is
less code, but `step` is read by anything that wraps a behaviour, as
`Delivery`, `DurableProducer` and the bank example all do. Recommended: the
deprecated getter.

A parameter name can't be deprecated. A caller that writes `step = { … }` as a
named argument breaks either way. In this repo that is `StepsSuite`, the bank
example and the internal wrappers. Most callers pass a trailing lambda and see
nothing.

## Stack

- [ ] **`spec-0222-turn`** — `Behaviour.turn`, the deprecated `step` getter, the
      `turn`/`turns` parameters, internal names, KDoc, and every caller in the
      repo moved over.
      Done when: nothing in the repo reads `Behaviour.step` (the build has no
      deprecation warning for it), and a test reading `behaviour.step` gets
      the same function as `behaviour.turn`.

## Acceptance

```bash
./gradlew build
git grep -n "\.step(this" -- 'actor/**' 'examples/**'   # prints nothing
```

## Open questions

1. **Deprecate, or break?** Recommended: the deprecated getter at `WARNING`
   level, as above.
2. **When does `step` go?** Recommended: deferred. A later spec raises it to
   `ERROR`, then removes it, once a release has carried the warning.
3. **`batching`'s `steps` parameter.** Rename to `turns`? Recommended: yes.
   It is only ever named by callers who already pass it by name, and the guide
   says "one turn" for a batch.
4. **`onStart`'s KDoc** says it is "a step with no message". Recommended:
   "a turn with no message", to match.

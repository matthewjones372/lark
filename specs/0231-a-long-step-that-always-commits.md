# 0231 — A long step that always commits

## Problem

A step (spec 0209) runs again whenever a value it read is committed by someone
else first. A step that reads many values, or runs for long, loses to every short
step that writes one of them. Under steady contention it can run again forever:
a report that totals 1,000 sections never commits while buyers keep holding seats.
Spec 0212 shows this in `surefold.transact.retries`, but nothing stops it. Today
the only way out is a lock around the whole thing, which is what `transact` was
meant to replace.

## Not doing

- **No change to a step that commits within its budget.** It stays lock-free, as
  today.
- **No snapshot reads.** Keeping old versions so read-only steps never conflict
  is a different engine. It would be a later spec.
- **No fairness between short steps.** Only the step that keeps losing is helped.
- **No timeout on a step.** A caller who wants one already has `timeout { }`.

## Shape

```kotlin
// Runs again at most 10 times (the default) before it runs alone.
val total = transact("report") { sections.sumOf { it.get() } }

// A step with its own budget.
transact("report", soloAfter = 3) { … }
```

After `soloAfter` runs that lost, the step's next run is **solo**. It sets a
global `solo` word, and while that word is set no other step can commit. So the
solo run reads values nobody else changes, and it commits first time. Other
steps that try to commit meanwhile fail, then park until the solo run ends, and
then run again.

The engine puts `solo` in every swap as one more word, expected empty, beside
the commit count. A commit that sees it taken fails like any other lost read.
The solo step takes the word in a swap of its own, and clears it in the swap that
commits its writes. A solo run that throws or fails clears it in a `finally`.
A solo run that calls `waitForChange` gives the word up first, then waits as any
step does.

A run that went solo is counted in `surefold.transact.runs`, tagged
`outcome=solo`, and the span event from spec 0212 gains `solo=true`.

## Why this shape

Running alone after N losses is the "serial irrevocable" mode other STMs fall
back to, such as GCC's libitm. Haskell's STM has nothing like it. It bounds the
worst case to N+1 runs. Lock-freedom is given
up only for the step that has already lost N times. Because the solo word is just
another word in the swap, no commit path gains a lock. **Alternative:** back off
the step that keeps losing (sleep, then retry). That is simpler, but it bounds
nothing, since the short writers never pause. **Alternative:** fail the step after N
runs with a declared error. That is honest, but it hands the caller the problem
`transact` exists to solve. Recommended: solo after N.

## Stack

- [x] **`spec-0231-solo`** ([#826](https://github.com/matthewjones372/surefold/pull/826)) — the `solo` word in every swap, a step going solo
      after `soloAfter` lost runs, and giving it up on throw, fail or wait.
      Done when: a step reading 1,000 values commits within 11 runs while 8
      threads commit to one of those values in a loop, and a Lincheck test of
      solo against ordinary commits finds no lost write.
- [x] **`spec-0231-report`** ([#830](https://github.com/matthewjones372/surefold/pull/830)) — the `solo` outcome tag, the `solo` span
      attribute, and a "When a step keeps losing" section in `docs/core.md`.
      Done when: a step forced solo records `outcome=solo` once, and the guide's
      fence compiles.

## Acceptance

```bash
./gradlew :surefold:check :surefold-otel:check
```

## Open questions

Taken as recommended (maintainer, 2026-10-27). Each answer is the question's recommendation.

1. **Default `soloAfter`?** Recommended: 10. Spec 0212's retry metrics should
   show nearly every step committing well inside that.
2. **Can it be turned off?** Recommended: `soloAfter = Int.MAX_VALUE` turns it
   off, and nothing more is needed.
3. **A solo run that blocks** (a slow computation) stalls every committer for as
   long as it runs. Recommended: document it, and rely on the `solo` tag to show
   it. Pre-empting a solo run would bring back the starvation this spec fixes.
4. **Nested `transact`.** Recommended: only the outermost step counts runs and
   goes solo. An inner one joins it, as today.

# 0047 — A stream the compiler sees whole

## Problem

Once 0046 lands, a backend receives the whole pipeline before it runs. Today's
Pekko encoding cannot use that: each operator builds its own stage when it is
called, and the stages never see each other. So `map → map → filter → map →
mapOrFail` becomes five Pekko stages, each with a push/pull hand-off per
element and its own `guarded` try.

A scratch measurement (Pekko 1.1.3, 2M elements, one machine, no JMH) put that
pipeline at about 150 ns per element as five stages and 88 ns as one merged
stage, with the same error messages. The same logic as a plain loop was 3–9 ns.
That is the fork backend's ceiling for a merged segment. It can only reach it by
knowing where each segment starts and ends.

## Not doing

- **No rewrite that a user can observe.** Element order, backpressure, what
  runs for each element, and what a defect names all stay as written. Rewrites
  that change any of those are listed under question 2, and none is on by default.
- **No cost model and no runtime profiling.** The rules are local patterns on
  the tree, applied once at compile time.
- **No rewrite across a `Native` node.** It is opaque, so the compiler stops at
  its edges.

## Shape

An internal pass in `lark-stream`, run by `start` before a backend compiles the
tree. Each backend sees the rewritten tree.

| Rule | Before | After |
|---|---|---|
| merge | `map`, `filter`, `mapOrFail`, `mapConcat`, `takeWhile` in a row | one `Fused` node: a step list, one guard |
| collapse | `take(a).take(b)`, `drop(a).drop(b)` | `take(min(a, b))`, `drop(a + b)` |
| collapse | `mapPar(1) { f }` | `mapOrFail { f }` |
| no failure | `catchAll`, `mapError`, `orElse` over a subtree that cannot fail | removed |
| no failure | a run whose tree cannot fail | no `DeclaredFailure` unwrap |

- A `Fused` node keeps each step's operator name and build site, and tracks the
  index of the step that is running. A defect in step 3 still reads
  `filter died on 7, built at Orders.kt:41`.
- On Pekko, `Fused` is one `GraphStage`. On forks, it is the body of the loop
  that the pulling thread runs.
- "Cannot fail" is structural. No `fail`, `mapOrFail`, `mapPar` with a raising
  body, `fromStage(ifNull)` or `Native` source appears beneath the node.
- `Stream.explain()` returns the tree before and after the pass, as text. It
  is the inspection hook for tests here and for 0049.

## Why this shape

Pekko already runs neighbouring stages on one actor, but it never merges them,
and a hand-off per stage is the cost that remains. Merging in lark's own tree
is only possible because 0046 made the tree data. The alternative is Pekko's
own `Flow.fromFunction` chains, which still pay the hand-off. Recommended
against.

The rules are deliberately few. Each one is a pattern and a proof that nothing
observable moved. A general optimiser would need a semantics for side effects
that lark does not have.

## Stack

- [ ] **`spec-0047-fuse`**: the pass, the `Fused` node, and its Pekko stage.
      Done when: the `map`/`filter` baseline row from 0046 is at least 30% faster, and
      `DefectTest` passes unchanged.
- [ ] **`spec-0047-collapse`**: the collapse and no-failure rules, and `explain()`.
      Done when: `explain()` shows each rule firing on a pipeline built to trigger it,
      and a rule that does not apply leaves the tree identical.
- [ ] **`spec-0047-gate`**: a JMH comparison in CI. Every baseline row must be at least
      as fast as the committed number.
      Done when: a deliberately slowed stage turns the build red.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build && ./gradlew :lark-stream-benchmarks:jmh
```

## Open questions

1. **Can the pass be turned off?** Recommended: yes, through
   `start(run, optimise = false)`, for debugging and for a bug report that needs
   the literal tree. The default is on.
2. **Opt-in rewrites that change behaviour.** Merging `mapPar(n).mapPar(n)` into
   one changes how many elements are in flight. Moving `take` ahead of a `map`
   runs fewer side effects. Recommended: neither, until a pipeline asks.
3. **A CI gate on timing.** Shared runners are noisy. Recommended: fail at 10%
   worse than the baseline, and record every run's numbers so drift is visible
   before it trips.

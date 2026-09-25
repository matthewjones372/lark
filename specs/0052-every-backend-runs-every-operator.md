# 0052 — Every backend runs every operator

## Problem

A description names no backend, but `Forks` refuses nine of its operators.
`TestStreams` refuses five. A pipeline that uses one of them fails at `start`
on those backends, so the choice of backend is not free. These are refused:

| Operator | Forks | TestStreams |
|---|---|---|
| `sliding`, `interleave` | refused | refused |
| `mapAsync` | refused | refused |
| `merge`, `flatMapMerge` | refused | refused |
| `conflate` | refused | refused |
| `tick`, `groupedWithin`, `restartOnDefect` | refused | runs (0048) |

Forks has had concurrency since 0051 (`mapPar`, `buffer`). Nothing yet checks
that no fork outlives its run under every way a run can end, or under load.

## Not doing

- **Pekko's own types.** A `Source`, `Flow` or `Sink` stays Pekko's only.
- **Unordered variants.** `mapParUnordered` and `mapAsyncUnordered` are left out.
- **Speed work.** Each gap is held to the benchmark gate, and no more.

## Shape

- **Hardening first.**
  - Leaks: a counting executor gives Forks its threads. Every parity case is
    then run three ways: to the end, cut short by `take`, and ended by `stop`
    from another thread mid-flight. Each is checked with no thread left alive
    and no body still running.
  - Soak: the parity cases run 200 times on Forks, to shake out races.
- **Pure pull, on both backends.** `sliding` and `interleave` need no second
  thread.
- **`mapAsync(n)`.** On Forks it is 0051's window of `n` stages, and a stage
  that completes with `null` is the defect. On `TestStreams` it awaits one at a
  time.
- **Fan-in.**
  - Forks: `merge` forks each input into one bounded queue, in arrival order.
    `flatMapMerge(breadth)` forks up to `breadth` inner streams into the same
    kind of queue.
  - `TestStreams`: each input is a worker taking turns, so the order is the
    same on every run.
- **`conflate`.** Upstream runs on a fork into a one-slot aggregate, and the
  reader takes whatever has piled up.
- **Time on Forks.** `tick`, `groupedWithin` and the restart delay wait on
  lark's `clock`, read when the run starts. `groupedWithin` pulls upstream on
  a fork, as it does on `TestStreams`.

## Why this shape

Every gap reuses what 0051 built: a fork with a bounded queue, and a per-run
`Releases` that the run lets go of before its exit completes. There are two
alternatives:

- A channel type from 0038. Not recommended: it was never built, and each
  queue here has one reader.
- Leaving these operators Pekko-only. Not recommended: then a description
  names its backend after all.

## Stack

- [ ] **`spec-0052-hardened`**: the leak-checking executor, the three-way
      parity run and the soak. Done when: all three pass on `mapPar` and
      `buffer`, and a leak planted on purpose turns the check red.
- [ ] **`spec-0052-pure`**: `sliding` and `interleave` on Forks and
      `TestStreams`. Done when: their parity cases run on all three backends.
- [ ] **`spec-0052-async`**: `mapAsync` on both. Done when: its parity case
      passes and at most `n` stages are in flight.
- [ ] **`spec-0052-fanin`**: `merge` and `flatMapMerge` on both. Done when:
      both parity cases pass on all three backends and under the leak check.
- [ ] **`spec-0052-conflate`**: `conflate` on both. Done when: a slow reader
      sees aggregates, and nothing is lost.
- [ ] **`spec-0052-time`**: `tick`, `groupedWithin` and restart on Forks. Done
      when: the parity suite refuses nothing on any backend except Pekko's own
      types.

## Acceptance

```bash
./gradlew build
lark-stream-benchmarks/gate.sh origin/main
```

## Open questions

1. **Merge order on `TestStreams`?** Recommended: the fixed turn order, where
   upstream comes first and then the order the inputs were given, so a test
   sees the same interleaving on every run.
2. **Which clock does Forks read?** Recommended: lark's `clock`, read at
   `start`, as a stage body would.
3. **Hardening before the gaps, or after?** Recommended: before, so each gap
   lands already under the leak check.
4. **Soak size?** Recommended: 200 runs in the normal build, and a
   `-Psoak=N` property for more.

Decided (2026-09-25): every open question goes as recommended. Merge order on
`TestStreams` is the fixed turn order; Forks reads lark's `clock` at `start`;
hardening lands first; the soak is 200 runs, with `-Psoak=N` for more.

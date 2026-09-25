# 0051 — Forks that fork

## Problem

`Forks` refuses `mapPar`, so a pipeline with a slow call in it runs one call at a
time, or runs on Pekko. The two backends were measured side by side
(`lark-stream-benchmarks`), and per element Forks is 29.9 ns and Pekko 91.1 ns.
The gap is too large to give up for the sake of one blocking stage.

`buffer` is Pekko-only. It takes Pekko's `OverflowStrategy`, so a description
that uses it can only run on Pekko.

## Not doing

- **No fan-in on Forks.** `merge`, `interleave` and `flatMapMerge` stay refused.
- **No clock on Forks.** `tick`, `groupedWithin` and restart delays stay refused
  there. `TestStreams` already runs them.
- **No overflow strategies in the core.** The core `buffer(size)` backpressures.
  Dropping or failing when full stays with Pekko's `buffer(size, strategy)`.
- **No `mapParUnordered`.**

## Shape

```kotlin
Stream.from(ids)
    .mapPar(8) { id -> fetch(id).bind() }   // at most 8 bodies at once, output in input order
    .buffer(256)                            // everything above runs ahead by up to 256 elements
    .runFold(0) { n, _ -> n + 1 }
    .run(Forks())                           // and the same description on PekkoStreams or TestStreams
```

- **`mapPar(n)` on Forks.** The pull loop keeps a window of up to `n` bodies in
  flight, each started on the node's executor (virtual threads by default). The
  window is refilled from upstream on the pulling thread. It emits results in
  input order, and pulls upstream only while fewer than `n` are in flight. Only
  the bodies run elsewhere, so upstream stays on one thread and its fused stages
  need no locks.
- **`buffer(size)` in `lark-stream`.** It is a new node, `Node.Buffer`.
  - Forks runs its upstream on a fork of its own, into a bounded queue.
  - Pekko compiles it to `buffer(size, backpressure)`.
  - `TestStreams` runs it as a worker taking turns, as `groupedWithin` does.
- **Failure.** A declared failure or a defect in a body or in the fork arrives
  where it would have in order, and ends the run as it would have without the
  concurrency.
- **Ending.** When the run ends (done, failed, a `take` satisfied, `stop`),
  in-flight bodies are interrupted and the buffer's fork is joined before the
  exit completes. No fork outlives its run.

## Why this shape

Concurrency appears only where it is written, and one pull loop stays in charge.
The alternative is 0039's `spec-0039-boundaries`: a `flock` and a channel at each
boundary. It is recommended against. The channels (0038) were never built, and a
single producer and consumer need only a bounded queue.

## Stack

- [ ] **`spec-0051-mappar`**: Forks runs `mapPar` with a window of `n`.
      Done when:
      - `mapPar(8)` keeps input order with never more than 8 bodies at once;
      - a failure in one body interrupts the rest and the run ends `Failed`;
      - the parity `mapPar` case runs on Forks;
      - `MapParBenchmark` has a Forks row beside Pekko's 8.7 µs.
- [ ] **`spec-0051-buffer`**: `Node.Buffer` and a core `buffer(size)`, on all
      three backends.
      Done when:
      - upstream runs ahead by at most `size` on each backend;
      - `take(3)` after a `buffer` ends the run with the fork joined;
      - the parity cases pass on all three backends.

## Acceptance

```bash
./gradlew build
lark-stream-benchmarks/gate.sh origin/main "MapParBenchmark|ChainBenchmark"
```

## Open questions

1. **Input order for `mapPar` on Forks?** Recommended: yes, as on Pekko.
2. **Honour `MapPar.on`, the executor a caller gave, on Forks?** Recommended: yes,
   so a body that must stay on a given pool still does.
3. **In-flight bodies at an early end: interrupt them, or let them finish and
   drop the results?** Recommended: interrupt, which is what lark's forks do.
4. **Should `buffer` on Pekko also add an async boundary?** Otherwise upstream
   does not run ahead concurrently there, as it does on Forks. Recommended: yes,
   `buffer(size, backpressure).async()`, so `buffer` means the same on every
   backend.

Decided (2026-09-25): every open question goes as recommended. `mapPar` keeps
input order on Forks and honours `MapPar.on`; in-flight bodies are interrupted
at an early end; `buffer` on Pekko is `buffer(size, backpressure).async()`.

# 0066 — A stream on actors

## Problem

lark-stream has three backends: Pekko Streams, Forks (a pull loop on one fork)
and `TestStreams`. lark-actor now has a runtime that beats Pekko's actors on
every row but a cold tell, yet no stream runs on it. So nobody can tell
whether an actor-shaped backend (Pekko's own design, without Pekko) is faster
or slower than Forks, or where. Forks starts a virtual thread for each
`mapPar` element (5.8 µs each), for each `merge` input, and for each `buffer`.
An actor that already exists costs a tell, about 150 ns.

## Not doing

- **Replacing Forks.** Forks stays the default and stays as it is. The new
  backend sits beside it, and the parity suite holds both to the same answers.
- **An actor per stage.** Linear stages are calls in one loop, as on Forks. A
  hop per stage would lose to Forks' 30 ns chain before anything else started.
- **Pekko interop, remote streams, stream refs.**
- **Speed work on lark-actor itself.** Spec 0065 owns that.

## Shape

```kotlin
flock<Nothing, Unit> {
    val backend = Actors(this)                    // runs cannot outlive the flock
    Stream.of(1, 2, 3).mapPar(8) { it * 2 }.runCollect().run(backend)
}
```

- **A run is an island.** One actor runs the fused chain with Forks' own pull
  operators, pulling up to `batch` elements (64) per step and then telling
  itself to go on. A long run yields its runner, and a stop lands between
  batches. A stage that blocks parks the runner, and the watcher grows as it
  does for any blocking step.
- **Boundaries are actors.** `buffer`, `mapPar`, `mapAsync`, `merge`,
  `flatMapMerge` and `conflate` run as child actors of the run's actor. They
  exchange batches of elements for credit: downstream grants `n`, and upstream
  sends at most `n`. `mapPar(n)` is a pool of `n` workers, not a thread per
  element.
- **Time is the flock's timers.** `tick`, `groupedWithin` and the restart
  delay use lark-actor's timers, which follow lark's `clock`.
- **Ending.** The exit completes once the run's actor and every child have
  ended. A stop stops the run's actor, whose children go first. A defect is
  the actor's failure, with no restart.
- **Compared.** `Actors` joins `ParityTest`, `HardenedTest` (leaks, stop
  mid-flight, the soak) and `DifferentialTest`. Every stream benchmark gains
  an `actors` row next to `forks` and `pekko`, and the benchmark README gets
  a three-column table.

## Why this shape

Pekko Streams is an actor per fused island, with demand passed as messages
across async boundaries. That is the design to measure here, and Forks is
the thing to measure it against. Fusing linear stages keeps the chain near
Forks', so the comparison is decided where the two actually differ: the
concurrent operators, the cost of starting a run, and many runs at once. An
alternative is a push-based stage per actor (Reactive Streams style). It is
simpler, but it loses the chain by an order of magnitude. Not recommended.

## Stack

- [ ] **`spec-0066-island`** — `lark-stream-actors`: a run is one actor
      pulling in batches, and every operator Forks runs without a fork.
      Refuses the rest by name. Done when: those parity cases pass, and
      the hardened checks leave no actor running.
- [ ] **`spec-0066-boundaries`** — `buffer`, `mapPar` and `mapAsync` as
      child actors on credit. Done when: their parity and differential cases
      pass, and at most `n` bodies are in flight.
- [ ] **`spec-0066-fanin`** — `merge`, `flatMapMerge` and `conflate`. Done
      when: their cases pass under the leak check and the soak.
- [ ] **`spec-0066-time`** — `tick`, `groupedWithin` and restart on the
      flock's timers. Done when: the parity suite refuses nothing on `Actors`.
- [ ] **`spec-0066-compared`** — an `actors` row in every stream
      benchmark, a baseline JSON, and the three-column table. Done when: the
      README says, row by row, where each backend wins.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-stream-benchmarks:jmh
```

## Open questions

- **Where does the flock come from?** `Actors(flock)`, so a run cannot outlive
  the scope it was started in, or an `Actors()` that owns a flock and is
  `close`d? Recommended: `Actors(flock)`, as lark scopes everything else.
- **Reuse Forks' pull operators?** That means `lark-stream-actors` depends on
  `lark-stream-forks`, and Forks' `Pull` becomes `@StreamSpi`. The
  alternative is to write push-style operators of its own. Recommended: reuse
  them, so that the two backends differ only at the boundaries being compared.
- **Batch and credit size?** Recommended: 64 for both, as the actor's
  throughput is, with a parameter on `Actors`. Tune it once the rows exist.
- **Numbering.** This takes 0066. Transport, membership and sharding move to
  0067–0069. Recommended: yes, as before.

Decided (2026-09-26): every open question goes as recommended. The backend is
`Actors(flock)`; it reuses Forks' pull operators, which become `@StreamSpi`;
batch and credit are 64, with a parameter on `Actors`; transport, membership
and sharding are 0067–0069.

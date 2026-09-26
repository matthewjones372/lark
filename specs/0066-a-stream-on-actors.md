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

- [x] **`spec-0066-island`** ([#147](https://github.com/matthewjones372/lark/pull/147)) — `lark-stream-actors`: a run is one actor
      pulling in batches, and every operator Forks runs without a fork.
      Refuses the rest by name. Done when: those parity cases pass, and
      the hardened checks leave no actor running.
- [x] **`spec-0066-boundaries`** ([#148](https://github.com/matthewjones372/lark/pull/148)) — `buffer`, `mapPar` and `mapAsync` as
      child actors on credit. Done when: their parity and differential cases
      pass, and at most `n` bodies are in flight.
- [x] **`spec-0066-fanin`** ([#149](https://github.com/matthewjones372/lark/pull/149)) — `merge`, `flatMapMerge` and `conflate`. Done
      when: their cases pass under the leak check and the soak.
- [x] **`spec-0066-time`** ([#149](https://github.com/matthewjones372/lark/pull/149), decided, not built) — `tick`, `groupedWithin` and restart on the
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

Decided while building `spec-0066-island` (2026-09-26), for editing:
- Forks' run loop is now `Pulling`, a `@StreamSpi` class that pulls a batch at
  a time and binds the run's resources and forks to whichever thread pulls it.
  Forks pulls it once to the end; `Actors` pulls `batch` per step.
- The island refuses nothing. The operators that run something beside the
  loop start it from `on`, as on Forks, until the entries below make each an
  actor. So `Actors` joins `ParityTest`, `DifferentialTest`, `HubTest` and
  every `HardenedTest` check now, rather than only once it runs everything.
  The hardened checks also assert that no run is left (`Actors.running`).
- A run's exit completes in its actor's `Stopping`, so it completes after
  every child the run spawned has stopped.

Decided while building `spec-0066-boundaries` (2026-09-26), for editing:
- A backend takes an operator over through `Boundaries`, a `@StreamSpi` hook
  that `Pulling` binds to the thread pulling a batch. `Actors` answers only on
  its run's own step, since only that step may spawn the run's children; a node
  built in a feeder's pull is left to Forks.
- `mapPar(n)` is `n` worker actors told in turn, and `buffer(n)` a feeder
  actor that pulls a batch at a time while it has room, and stalls until the
  reader takes one. A `mapPar` given an executor of its own keeps it.
- `mapAsync` stays as it is on Forks: a window of stages the loop awaits,
  which starts no thread, so there is nothing for an actor to take over.

Decided while building `spec-0066-fanin` (2026-09-26), for editing:
- `merge` is an input actor per stream into one queue with room for 16
  elements, as on Forks; `flatMapMerge(breadth)` is an outer actor that starts
  each inner stream as an input actor of its own, and `conflate` an actor that
  folds a batch a step into what is pending.
- The module shares its package with lark-stream and lark-stream-forks, where
  a private class is still a class of the package: a `Confluence` of its own
  was loaded in place of Forks' and broke `merge` on Forks. A test now fails
  on any class name two of those modules both have.
- Time stays on the run's clock, as on Forks: `tick` and the restart delay
  park the run's step until their instant, and `groupedWithin` forks its feed
  from `on`. The flock's timers were the plan, but a pull must hand back an
  element, so a pull waiting on a timer's message would park all the same; an
  actor's timer would add a hop and take nothing away. `ActorsTest` holds the
  three to their answers on `Actors`, and the parity suite already did.

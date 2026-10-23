# 0175 — A blocking body costs what a virtual thread costs

## Problem

[blocking-vs-reactive](https://github.com/matthewjones372/blocking-vs-reactive) shows `mapPar` using 2.6–3× Reactor's
CPU per element on the shared device: 22.0 against 8.5 µs at 16 in flight, 7.49 against 2.57 at 10,000. It also
allocates about 1.4 KB per element against Reactor's 0.9. `mapAsync` already beats Reactor, so the gap is the cost
of a blocking body. The guess was that forking a fresh virtual thread per element, and the `InFlight` around it,
dominates, and that a window of long-lived workers would close the gap.

## Not doing

- No change to `mapPar`'s behaviour: order, at most `parallelism` in flight, a failure interrupting the rest,
  `Releases`, `mapParUnordered`, `mapPar(limit)`, parity with the other backends.
- No spinning that holds a carrier, and no weaker wake protocol.
- Not the memory per blocked call (the blocked stack), except not making it worse.
- Nothing in `mapAsync` (`Awaited.kt`), which another branch is working on.

## Shape

No API change. A JFR CPU-time profile of the benchmark's `fanout-10k`, and a contestant written for the purpose,
`vt-floor` (p long-lived virtual threads looping over `blockingIo`, with no source, order or bookkeeping), came
first:

- Reactor runs the whole pipeline on the device's two threads. Lark's device-thread share alone is about Reactor's
  whole cost. The rest is the virtual-thread machinery any blocking body pays: park and freeze, an unpark from a
  platform thread into the scheduler, mount, and carriers going idle and being woken.
- Lark's own code (`InFlight`, the ring, the window, `either`) is 5–8% of the samples.
- `vt-floor` costs 14.2 µs/op at 10,000 in flight against Lark's 13.0, and 23.0 against 28.8 at 16. Long-lived
  workers are no cheaper at high concurrency, and could save at most a fifth at low concurrency.

So the change is the per-element bookkeeping Lark adds on top of the fork, one piece at a time, each measured:

- `InFlight` as one object: its answer, its runner and its waiters, in place of a `CompletableFuture`, a
  `CountDownLatch` and a monitor.
- Anything else the profile shows on the per-element path, kept only if a benchmark moves.

## Why this shape

A pool of workers would change what a body can rely on, as well as cost more code. A body would no longer run on a
thread of its own, so a `ThreadLocal` it set, an MDC entry or a tracing context left behind, would leak into the
next element on the same worker. A body's interrupt would have to be scoped to one element on a thread that
outlives it. The floor says that buys nothing at 10,000 in flight. Recommended: keep a thread per body, and cut
what Lark adds around it.

## Stack

- [x] **`spec-0175-mappar-cpu`** ([#508](https://github.com/matthewjones372/lark/pull/508)) — the profile, the floor,
      and the bookkeeping cuts that measure.
      Done when: blocking-vs-reactive's `lark` rows on `backpressure`, `fanout-10k` and `inflight-100k` (shared
      and native) are no worse in CPU or allocation, `MapParBenchmark` and `EnrichThroughputBenchmark` pass the
      gate, and the PR says how much of the gap to Reactor is the virtual thread's and how much is Lark's.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :lark-stream-forks:build :lark-stream-parity:build
bench/lark-stream-benchmarks/gate.sh origin/main "MapParBenchmark|EnrichThroughputBenchmark|HandOffBenchmark"
```

## Open questions

- **Is a pool of workers for small windows worth a spec of its own?** It could save up to a fifth at 16 in
  flight. Recommended: no, unless a user needs it, because of the `ThreadLocal` leak above.
- **Should blocking-vs-reactive report the floor?** `vt-floor` makes "this is the virtual thread's cost, not
  Lark's" a measured claim. Recommended: yes, as a contestant there, in a change of its own.

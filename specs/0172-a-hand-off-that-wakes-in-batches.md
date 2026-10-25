# 0172 — A hand-off that wakes in batches

## Problem

Every Forks operator that crosses a thread moves one element at a time and wakes a thread for each. `buffer` puts
and takes through an `ArrayBlockingQueue`. The `mapPar` and `mapAsync` windows offer into a `LinkedBlockingQueue`,
take a `Semaphore` permit per element, and the puller parks on each head's future in turn. A JFR profile of
`mapAsync` at 10k in flight in `blocking-vs-reactive` puts most of the CPU samples in that wake-up machinery
(`AQS.signalNext`, `VirtualThread.unpark`, `ForkJoinPool.signalWork`), not in user code.

Measured against Reactor on the same machine (`blocking-vs-reactive`, JDK 25, 4 cores):

| | Lark | Reactor |
|---|--:|--:|
| one million elements across a thread boundary (`buffer` / `publishOn`) | 520 ms | 25 ms |
| `mapAsync` at 10k in flight, ops/s | 390–520k | 549k |
| `mapAsync` at 10k in flight, CPU µs per element | 4.6–6.0 | 2.4 |

Reactor's advantage is batching, not a different threading model. A consumer drains everything that is ready
before it sleeps, and a producer is told about room only in chunks (prefetch, replenished at 75%).

## Not doing

- No change to any operator's API or semantics: order, failure, `stop()` and release behave as today.
- No lock-free queue from a library: `lark-stream-forks` adds no dependency.
- Not Actors, Pekko or TestStreams: they have their own hand-offs.
- Not unordered variants (0170) or adaptive limits (0171).

## Shape

No new public API. One internal primitive, `Ring(capacity)`, used by `buffer` and by both windows:

- One producer and one consumer.
- `put` parks only when the ring is full. A parked producer is woken once `capacity / 4` slots are free, not one
  slot at a time.
- `take` drains whatever is ready without parking. It parks only when the ring is empty, and is woken on the
  empty-to-non-empty transition, not on every put.
- An end marker and a failure travel in order behind the last element, as `Ended` does today.

For the windows, the head-of-line wait moves into the hand-off. Each started element owns a slot. Its completion
(a stage's callback, or a body's last line) writes its answer to the slot, and wakes the puller only if the puller
is parked on that slot. Room for the feeder is released in batches of `parallelism / 4`, at least 1.

## Why this shape

One primitive and three uses, so the batching rule is written and tested once. The alternative is tuning each
operator separately (bigger queues, `drainTo`). That is smaller per change, but it leaves the per-element wake
cost in place, and that cost is what the profile shows. Recommended: the primitive.

## Stack

- [x] **`handoff-ring`** ([#479](https://github.com/matthewjones372/lark/pull/479)) — `Ring` with its batching rule, end marker and interrupt behaviour, and unit tests.
      Done when: its tests pass, including a producer interrupted while parked on a full ring.
- [x] **`buffer-on-handoff`** ([#480](https://github.com/matthewjones372/lark/pull/480)) — `buffer` on Forks moves to `Ring`.
      Done when: `lark-stream-parity` is green and a new hand-off row in `lark-stream-benchmarks` is at least 5×
      faster than today's baseline.
- [x] **`mapasync-on-handoff`** ([#481](https://github.com/matthewjones372/lark/pull/481)) — the `mapAsync` window uses slots and batched room.
      Done when: parity is green and `MapParBenchmark`'s `mapAsync` rows pass the regression gate (`gate.sh`).
- [x] **`mappar-on-handoff`** ([#482](https://github.com/matthewjones372/lark/pull/482)) — the `mapPar` window, with each body's answer written to its slot instead of a
      per-body `CompletableFuture` and `CountDownLatch`.
      Done when: parity is green and `mapPar` allocates fewer bytes per element than today (JMH `-prof gc`).

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
bench/lark-stream-benchmarks/gate.sh
# in blocking-vs-reactive, against a locally published snapshot:
scripts/run-jmh.sh && scripts/run-io.sh
```

## Open questions

- **What does "beats Reactor" mean for the hand-off row?** Recommended: aim for within 2× of Reactor's 25 ms, and
  report whatever results. A blocking hand-off between virtual threads may never match a lock-free queue that
  spins, and promising that up front would be a guess.
- **Should the consumer spin briefly before it parks?** Recommended: no in the first cut. Spinning burns a carrier
  that other virtual threads need; revisit only if the profile still shows parking.
- **Should a `stop()` arriving mid-batch discard the elements already handed off?** Recommended: yes, as today. A
  stopped run reads nothing more.

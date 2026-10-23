# 0174 — A window that refills without a hop

## Problem

[blocking-vs-reactive](https://github.com/matthewjones372/blocking-vs-reactive)'s `backpressure-native` row (16 calls
in flight, 1 ms each) has `mapAsync` at 4,988 ops/s against Reactor's 14,650, and `fanout-1k-native` has it at 81k
against 98k. Most of that is not Lark's. The row's async call for Lark is `Patterns.after` on Pekko's scheduler,
which ticks every millisecond, and the timer alone, with no stream around it, does 4,880 ops/s at 16 in flight and
81k at 1,000. Pekko's contestant waits on the same timer, which is why it scores the same. On a JDK timer
(`ScheduledThreadPoolExecutor`), `mapAsync` does 12.6k at 16 in flight and 95.6k at 1,000.

The ~10% that's left is Lark's. It shows on the shared device as well (12.5k against Reactor's 13.8k). A trace of
each element at 16 in flight shows the bare timer starting a new call 0.3 µs after a completion, and `mapAsync`
taking a median of 122–157 µs. That time goes on two wake-ups in a row, each of a parked virtual thread:

| step | median |
|---|--:|
| the timer thread completes the head's stage, and the pull wakes | 68–87 µs |
| the pull's `poll` makes room, and the feeder wakes and starts the next call | 49–63 µs |

Reactor makes both steps on the completing thread, and Pekko makes one of them.

## Not doing

- No spinning. A feeder that yields instead of parking reaches 13.9k, but costs 186 µs of CPU per call against 21.
- No change to the room batch. Waking the feeder on every `poll` measured the same as today (12,572 against 12,488):
  batched starts complete in bursts, so the batch wake comes right after a burst anyway.
- No change to `buffer`, `mapPar`, `mapAsyncUnordered` or how many elements a window holds.
- No change to the first step. The pull has to wake to pass the answer on.

## Shape

No API change. In the ordered `mapAsync` window, the feeder pulls the next element before it waits for room, and
leaves the element where the pull can claim it. The pull, once its `poll` has made room, claims that element,
starts its stage, and puts it in the ring, then wakes the feeder to pull the one after. The second step leaves the
critical path.

A throwaway prototype measured 13,261 against 12,618 (+5.1%) on the JDK timer at 16 in flight.

## Why this shape

It removes the one wake-up that can go without spinning. The cost is that the caller's function then sometimes
runs on the pull's thread instead of the feeder's. A function that blocks would hold up answers that are already
done, and at 10,000 in flight the pull would do most of the starting, work the feeder now does in parallel with
it. The alternative is to leave the window as it is and document the ~10% at small windows as the price of a pull
on virtual threads. Recommended: the alternative, unless the open questions below come out in favour. Five percent
is under the benchmark's own 20% noise caveat, and the change adds a second producer to `Ring`'s wake protocol.

## Stack

- [ ] **`spec-0174-async-few-in-flight`** — this spec, with the measurements, and no code.
      Done when: reviewed and either cut or committed.
- [ ] **`mapasync-pull-starts`** — only if kept: the hand-over above, with a test that fails when the hand-over's
      wake is dropped, in the style of `RingTest`'s lost-wake test.
      Done when: `backpressure-native` on the JDK timer gains ≥ 4%, and `gate.sh` shows no regression in
      `MapParBenchmark`, `HandOffBenchmark` or `EnrichThroughputBenchmark`, nor blocking-vs-reactive's
      `fanout-10k`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :lark-stream-forks:build :lark-stream-parity:build
bench/lark-stream-benchmarks/gate.sh origin/main "MapParBenchmark|HandOffBenchmark|EnrichThroughputBenchmark"
```

## Open questions

- **Should blocking-vs-reactive's native row give Lark a JDK timer?** Recommended: yes. Lark has no timer of its
  own, and `CompletableFuture.completeOnTimeout` is the JDK's. Pekko keeps its own scheduler. The README already says
  the row is "partly Pekko's scheduler"; the summary table doesn't.
- **May `mapAsync`'s function run on the pull's thread?** Recommended: no promise either way. Nothing documents or
  tests which thread it runs on, but a function that blocks is the case to settle first.
- **Is ~5% at windows of a few dozen worth a second producer in the ring?** Recommended: not on its own. Revisit
  if a real client (HTTP, JDBC) at low concurrency shows the same two steps.

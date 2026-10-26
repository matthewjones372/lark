# 0185 — A merge that keeps up

## Problem

Specs 0172 and 0173 moved `buffer` on Forks onto `Ring`, and `HandOffBenchmark.bufferForks` fell from about 259 to
90 ns per element. `merge` still hands every element from its two input forks to the reader through one
`ArrayBlockingQueue` of 16, and reads about 880–940 ns per element in the gate, ten times `buffer`. A JFR profile of
`mergeForks` puts most samples in the queue's lock (`ReentrantLock`, `AbstractQueuedSynchronizer`) and in parking and
unparking a virtual thread for nearly every element (`ForkJoinPool.signalWork`, `Continuation.yield`): the costs
`Ring` was built to avoid.

## Not doing

- No change to `merge`'s behaviour or API: an element is read once, an input's failure ends the run and lets go of
  the other, the run ends once both inputs have, and `stop()`, interrupts and `Releases` behave as today.
- Not `flatMapMerge`: its inner streams come and go while it runs, which a fixed set of rings does not fit. It
  stays on the queue, and can follow if this lands.
- Not the test clock's `mergedOnClock`, nor Actors, Pekko or TestStreams.
- No relaxing of `Ring`'s wake protocol, and no spinning that holds a carrier beyond the few yields it does today.

## Shape

No API change. Inside `Merged.kt` and `Ring.kt`:

- Each input of a `merge` puts into a `Ring` of 64 of its own, with its end or failure behind its last element.
- The reader takes from the rings in turn, one element each while more than one has some, so a busy input cannot
  starve a slow one. It drops a ring from its turns once that ring's end marker has been read.
- The reader parks only when every ring is empty. The rings share one place where it names itself, so whichever
  input puts next wakes it.

## Why this shape

Three ways to hand elements from many forks to one reader:

- **A ring per input, read in turn** (recommended). Each ring keeps `Ring`'s single-producer protocol unchanged,
  so its proof carries over; only the reader's park looks at several rings. Turns give stricter fairness than
  arrival order.
- **One ring with many producers.** Every put contends on one tail with a compare-and-set, and a parked producer
  needs a list of waiters rather than one name. More code, and both producers write the same cache line.
- **`Arrivals`** (spec 0170). A `ConcurrentLinkedQueue` allocates a node per element, and its room is counted for
  one feeder only.

Recommended: a ring per input, with the other two measured as probes and reported.

## Stack

- [x] **`merge-on-a-ring`** — the rings, the reader's turns and shared park, a test that fails if a wake is lost,
      and the benchmark row before and after each change.
      Done when: `HandOffBenchmark.mergeForks` is within 2× of `bufferForks`, or the PR says what is left and why.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold-stream-forks:build :surefold-stream-parity:build
bench/surefold-stream-benchmarks/gate.sh origin/main "HandOffBenchmark|MapParBenchmark|EnrichThroughputBenchmark"
```

## Open questions

- **How much room does each input get?** Today the two share 16. Answered by measuring: 64 each, as much as the
  benchmark's `buffer(64)`. At 16 each a ring wakes its producer every fourth element, and `mergeForks` read about
  155 ns per element; at 64, about 90; at 256, about 70. 256 lets a merge hold 512 elements, which a stream of large
  elements would feel, so 64 is the recommendation.
- **Should the reader take a batch from one ring before moving on?** Answered: no. Sixteen at a time read about 12%
  faster (70 against 80), which does not buy back one-each fairness.
- **Is within 2× of `bufferForks` the bar?** Answered: yes, and it is met: run side by side, the two rows read
  within about 1.3× of each other.

# 0173 — A hand-off that keeps up

## Problem

Spec 0172 moved `buffer` on Forks onto `Ring`, and a million elements across a thread boundary went from 520 to
137 ms. Its target was within 2× of Reactor's `publishOn`, and it missed: Reactor takes 25 ms
([blocking-vs-reactive](https://github.com/matthewjones372/blocking-vs-reactive), JMH, same machine). A standalone
copy of the ring did 50 ms, so most of the gap is in how Forks uses it, not in the ring's design.

Reading the ring turns up two costs it was built to avoid:

- `offer` checks for room against the consumer's live index, so every put reads a cache line the consumer writes on
  every take. Caching the other side's index exists to avoid exactly that.
- `take` is `awaitHead` and then `poll`, so every element is looked up twice.

## Not doing

- No change to any operator's behaviour, and no new API.
- No spinning that holds a carrier: a few yields, as today, and then a park.
- No relaxing of the wake protocol. The producer still fences on every put, because one element may be all the
  consumer gets for a while, and a missed wake would stall a run.

## Shape

No API change. Inside `Ring` and `buffered`:

- `offer` checks room against the head `awaitRoom` last read, which `awaitRoom` has just made sure is enough.
- `take` reads, clears and advances in one pass.
- Anything else a profile of `HandOffBenchmark.bufferForks` shows on the per-element path, one change at a time,
  each kept only if the benchmark moves.

## Why this shape

Profile first and change one thing at a time, because the first ring showed that a change which looks right can
make things slower: batching the `mapAsync` window's room without the yields lost throughput. The alternative is a
different hand-off altogether, such as an array of slots with sequence numbers in the style of the LMAX disruptor.
That's more code to get right, and is worth it only if this one can't close most of the gap. Recommended: tune this
one first.

## Stack

- [x] **`spec-0173-handoff-keeps-up`** — the changes above, with the benchmark row before and after each one.
      Done when: `HandOffBenchmark.bufferForks` and blocking-vs-reactive's hand-off row are within 2× of Reactor's
      25 ms, or the PR says what is left and why.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :lark-stream-forks:build :lark-stream-parity:build
bench/lark-stream-benchmarks/gate.sh origin/main "HandOffBenchmark"
```

## Open questions

- **Is within 2× of Reactor still the bar?** Recommended: yes, and report the measured number whichever side of it
  lands.

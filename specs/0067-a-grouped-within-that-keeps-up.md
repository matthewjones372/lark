# 0067 — A groupedWithin that keeps up

## Problem

0066's comparison measured `groupedWithin(100, 1.seconds)` at 25 µs an element
on Forks and 24 µs on Actors, against 184 ns for the same description on
Pekko: 130 times slower, and the only row where either lark backend loses to
Pekko. The cost is the feed, not the backend. On real time, upstream is pulled
on a fork of its own into a one-element handoff, and every element is two
parks and two wakes: the feed waits for the reader to take it, and the reader
waits for the next. Anyone batching a fast source on Forks pays this.

Transport, membership and sharding move to 0068–0070.

## Not doing

- **A test's clock.** On `TestStreams` the one-element handoff is what makes a
  window close at the same element on every run. It stays.
- **Changing what a window is.** Windows follow each other every `within` from
  the first pull, a full group starts the next from when it was emitted, an
  empty window emits nothing, and a failure is thrown at once.
- **`tick` and `restartOnDefect`.** They have no feed.

## Shape

On real time (Forks and Actors), the feed puts each element into a queue with
room for a group, `n` elements, and parks only when it is full. The reader
takes whatever is queued without parking, and parks only on an empty queue,
until the window closes. A full queue is a full group, so the feed is never
more than one group ahead of the reader.

## Why this shape

The window's rules stay in the reader, and the only change is how far ahead
the feed may run. A queue of `n` is Pekko's own bound for this operator: it
pulls ahead up to its group. Keeping the handoff and waking less often was the
other option. Not recommended: each element would still cross threads alone.

## Stack

- [x] **`spec-0067-queued`** ([#151](https://github.com/matthewjones372/lark/pull/151)) — the queue on real time, and a test that a window
      on real time closes with what it held while upstream is blocked. Done
      when: `GroupedWithinBenchmark`'s `forks` row is within twice the Pekko
      row, and the parity and `TestStreams` suites pass unchanged.

## Acceptance

```bash
./gradlew build
./gradlew :lark-stream-benchmarks:jmh -PbenchmarkArgs=GroupedWithinBenchmark
```

## Open questions

- **Room for `n`, or a fixed size?** Recommended: `n`, as Pekko bounds it.

Decided (2026-09-26), since the user asked for problems found on the way to be
specced and fixed: room for `n`.

Decided while building (2026-09-26): the reader drains whatever is queued in
one go and reads the clock only when the queue is empty; taking one element
at a time, with the clock read for each, was 1,174 ns an element. Drained, the
`forks` row is 317 ± 33 ns against the Pekko row's 178 ± 14 ns, from 25,006.

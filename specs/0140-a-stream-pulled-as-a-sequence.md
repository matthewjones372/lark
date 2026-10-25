# 0140 — A stream pulled as a sequence

## Problem

A lark `Stream` reaches code that pulls, a Kotlin `Sequence` or `Iterator`, only through Pekko:
`lark-stream-pekko` has `toSource()`, and nothing in `lark-stream` hands elements to a puller. Pelican's Jetty
backend streams a response from a `Sequence`, so a service serving a lark `Stream` there has to write the bridge
itself. Petshop did (`Pulled` in `api/.../Jetty.kt`, PR matthewjones372/petshop#74): a run on the service's
`StreamBackend` filling a bounded queue, an iterator taking from it, and a `close` that stops the run. The parts
are easy to get quietly wrong: an unbounded queue loses backpressure, a missed `close` leaves the run going after
the reader has left, and a failure has to cross to the reader's thread.

## Not doing

- **Streams that can fail with a declared `E`.** `Stream<Nothing, A>` only, as `toSource()`. A declared failure
  is a value, and a `Sequence` has no place for it.
- **A pushing bridge (`Flow`, `Publisher`).** Pulling only.
- **Anything Pelican-shaped.** Pelican's binders take a `Sequence` already.

## Shape

In `lark-stream`, beside `run` and `start`:

```kotlin
fun <A : Any> Stream<Nothing, A>.toSequence(backend: StreamBackend, buffer: Int = 16): Sequence<A>

// a Jetty response from a lark stream
events streamedNow { watch().toSequence(streams) }
```

- Iterating starts one run on `backend`. The sequence is constrained to one iteration; a second throws.
- At most `buffer` elements wait between the run and the reader. A reader that stops pulling holds the run.
- The iterator is `AutoCloseable`. `close()` stops the run, from any thread, and unblocks a producer waiting on
  a full buffer. Pelican's Jetty writer already closes an `AutoCloseable` iterator however the response ends.
- A run that dies throws its cause from the reader's next `hasNext()`. A run stopped by `close()` throws nothing.

## Why this shape

`toSource()` is the Pekko answer and needs no backend because Pekko materialises later. A `Sequence` has nobody
to run it, so the backend is a parameter, the same one `run` and `start` take. Putting it in `lark-stream` keeps
it free of Pelican and of Jetty: anything that pulls can use it. The alternative is a Pelican binder that takes a
lark `Stream`, which would tie Pelican to lark for one adapter. Recommended: lark.

## Stack

- [x] **`spec-0140-to-sequence`**: `Stream.toSequence(backend, buffer)` in `lark-stream`, with tests on Forks
      and on the Pekko backend through `lark-stream-parity`.
      Done when: a stalled reader holds the run (a gate, no clock), `close()` mid-stream stops the run and
      unblocks a full buffer, a dying run's cause reaches the reader, and the cookbook shows the Jetty case.

## Acceptance

```bash
./gradlew build
```

## Decisions

Matt asked for this on 2026-10-15: users should not write the bridge. Pelican spec 0081 is the companion.

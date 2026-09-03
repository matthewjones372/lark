# 0007 — A run to a named sink

## Problem

`divertLefts(to = sink)` sends every `Left` to a sink the caller named, and
the `Right`s stay in the stream — where they can only be folded or collected.
A pipeline whose rights belong in a second named place (a `Sink.foreach`, a
producer, a file — anything Pekko already gives) has to leave dipper's world
through `toSource()` to get there, and with it loses `Exit`. Splitting an
`Either` into two named destinations is the ordinary case, and half of it is
missing.

## Not doing

- **No N-way split and no split by predicate.** Three destinations, or a
  routing rule, is Pekko's `Partition`/`divertTo` through `toSource()`; this
  library types the `Either` split and nothing else.
- **No second materialised value on `Run`.** `Run<E, R>` carries one; the
  sink's is it.
- **No sinks of lark's own.** Every destination is a Pekko `Sink`.

## Shape

```kotlin
Stream.from(rows)
    .mapAsync(4) { ledger.settle(it) }        // Stream<E, Either<Declined, Receipt>>
    .divertLefts(to = declinedSink)           // Stream<E, Receipt>
    .runWith(receiptSink)                     // Run<E, Done>
    .run(system)                              // CompletionStage<Exit<E, Done>>
```

- `fun <E, A : Any, M> Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M>`
  — a run described, to the sink named; the sink's materialised value is the
  run's, so `Sink.foreach` answers `Done`, `Sink.seq` a `List<A>`, and a sink
  the caller wrote answers whatever it declares.
- Both sinks materialise in the one `run`, so `Exit` covers both: a failure
  anywhere is `Failed(e)`, a throw `Died(cause)`, and no sink is left
  half-written unheard.
- The operator table gains the row; the README example ends in `runWith`
  rather than a fold, since counting receipts was standing in for sending
  them somewhere.

## Why this shape

`runCollect` and `runFold` are `runWith(Sink.seq())` and `runWith(Sink.fold())`
with the name written out; adding the general form beside them is the small
change, and rewriting them over it keeps one implementation. Requiring the
sink's materialised value to be a `CompletionStage<M>` is what lets `run`
answer an `Exit` for it: a sink with a synchronous materialised value has
nothing to wait for and nothing to fail with, and `Sink.ignore`, `foreach`,
`seq`, `fold`, `head` and every producer sink are stage-shaped.

## Stack

- [x] **`spec-0007-runwith`** (`main`, 5e30010) — `runWith`; `runCollect`/`runFold` rewritten
      over it; the table row; the README example.
      Done when: lefts reach one named sink and rights another in one run, the
      run's `Exit` is `Failed(e)` when an element fails after both sinks have
      taken elements, and dipper's suite passes unedited.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — decided building it: a sink whose materialised value is not a
`CompletionStage` is refused at the type, since `run` would have nothing to
wait on.

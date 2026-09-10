# 0013 — The operators a pipeline reaches for

## Problem

The surface is what two consumers happened to need. Spec 0011 says so in as
many words — "what one consumer needed, and the mirror of one of them" — and
every operator since has arrived the same way. Read against Pekko's own
operator index, what is missing is most of the everyday middle of a pipeline:
`take`, `drop`, `grouped`, `groupedWithin`, `buffer`, `scan`, `alsoTo`, and
the mapping of one failure type onto another.

None of that is a small gap here, because leaving the typed world to get it
costs the failure type. `toSource()` is declared on `Stream<Nothing, A>`, so a
stream carrying an `E` has to `either()` first, and the pipeline is back to an
`Either` in every element — the shape `docs/stream.md` opens by arguing
against. A missing `take` therefore costs the same as a missing
`flatMapConcat` (spec 0012): not a wrapper, but the whole typed world for the
rest of the graph.

## Not doing

- **Not Pekko's operator set re-exported.** Every row is one line over Pekko
  and one more row anyone has to keep agreeing; the ones below are the ones a
  pipeline reaches for, and the rest stay behind `toSource()`.
- **No fan-in.** `merge`, `zip`, `interleave` join two failure types and
  Pekko's `japi.Pair` leaks into the element; that is its own spec.
- **No sub-streams.** `groupBy`, `splitWhen` and friends, as spec 0012 says.
- **No supervision and no `resume`.** A dropped element is still the thing
  this library exists to refuse.
- **No new sinks or runners.** `runWith(Sink.x())` already says them.

## Shape

```kotlin
orders
    .filterNot { it.isTest }                       // Stream<E, Order>
    .take(1_000)
    .groupedWithin(100, 5.seconds)                 // Stream<E, List<Order>>
    .buffer(16, OverflowStrategy.backpressure())   // no dropping strategy offered
    .alsoTo(auditSink)                             // a copy out, the element onwards
    .scan(Totals.zero) { totals, batch -> totals + batch }
    .mapError { e -> Ingest.Upstream(e) }          // Stream<IngestError, Totals>
```

- **Bounds:** `take(n)`, `drop(n)`, `takeWhile(p)`, `dropWhile(p)`,
  `filterNot(p)` — `filter`'s siblings, predicates through the 0010 guard.
- **Batching:** `grouped(n)`, `sliding(n, step)`, `groupedWithin(n, within)`,
  `buffer(size, strategy)`. The element becomes a `List<A>`, which is `Any`.
- **Carrying state:** `scan(zero, f)` and `statefulMap`, both caller code and
  both guarded, naming the element being folded in as `conflateWithSeed` does.
- **Taps:** `alsoTo(sink)` and `wireTap(sink)` — a copy out to a Pekko `Sink`,
  the element onwards; sinks stay Pekko's, as `divertLefts` has them.
- **The failure type:** `Stream<E, A>.mapError(f: (E) -> E2): Stream<E2, A>`.
  Today the only way to rename a failure is `catchAll { Stream.fail(f(it)) }`,
  which reads as recovery and is not.
- On `Pipe` first in both forms, `Stream`'s as `via`, per spec 0008.

## Why this shape

The alternative is to open `toSource()` on any `Stream<E, A>` and let callers
reach Pekko directly for the long tail. That is one line instead of thirty
rows, and it is the wrong one: the gate is what makes a failure impossible to
lose, and widening it to buy convenience trades the library's only real
guarantee for typing less. Adding the operators a pipeline actually reaches
for keeps the gate shut and keeps the list finite.

## Stack

- [x] **`spec-0013-bounds`** — `take`, `drop`, `takeWhile`, `dropWhile`,
      `filterNot`. Done when: a bounded stream ends after `n` and a throw in
      a predicate dies naming the operator.
- [x] **`spec-0013-batching`** — `grouped`, `sliding`, `groupedWithin`,
      `buffer`. Done when: a slow consumer sees batches, not elements.
- [x] **`spec-0013-stateful`** — `scan`, `statefulMap`. Done when: a running
      total emits per element and a throw in the body names the element.
- [x] **`spec-0013-taps`** — `alsoTo`, `wireTap`. Done when: an audit sink
      sees every element and the pipeline is unchanged.
- [x] **`spec-0013-map-error`** — `mapError`. Done when: a stream's `E`
      becomes another `E` without a `catchAll` that means recovery.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Which of these does anyone actually reach for?** `sliding`,
   `statefulMap` and `wireTap` are here because Pekko has them, not because a
   consumer asked. Recommend cutting all three from the first pass and adding
   each when a pipeline names it.
2. **`buffer` with a dropping `OverflowStrategy` is the silent drop this
   library bans.** `dropHead`, `dropTail`, `dropBuffer` and `dropNew` all lose
   an element and say nothing — exactly what the `Resume` decider does.
   Recommend refusing them: take no `OverflowStrategy` at all and offer
   `buffer(size)` as backpressure, with `bufferOrFail(size, error)` as the
   declared-failure form. The alternative — pass Pekko's enum straight
   through — puts a supported spelling on the drop.
3. **`limit(n)` fails with Pekko's `StreamLimitReachedException`, so it
   arrives as `Died`.** Recommend leaving `limit` out and spelling it
   `orFailIfLongerThan(n, error)` if anyone wants it, so the bound is a
   declared failure rather than a defect.
4. **Is `mapError` enough, or is `catchSome` wanted beside it?** Recommend
   `mapError` alone: `catchAll` with a `when` already handles some and
   re-fails the rest.
5. **Five stack entries or fewer, larger ones?** Recommend five: each is a
   handful of rows and stays well inside the 200-line cap.

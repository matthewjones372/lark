# 0011 — The seams the first consumer needed

## Problem

The first service to adopt the stream kept three of its pipelines half on
the `Source` side. `Source.tick` materialises a `Cancellable`, and
`Stream.from` accepts only a `Source<A, NotUsed>`, so both poll loops read
`Source.tick(...).mapMaterializedValue { NotUsed.getInstance() }` before the
stream begins. Two sockets open with current state through `prepend`, and
the cart-change feed collapses a backlog with `conflateWithSeed` and
`mapConcat`; none of the three has a spelling here, so each pipeline wraps
and unwraps around them. Every unwrapped stretch is one where a defect is
unnamed and a `null` is a drop again.

Drafted as dipper's spec 0004 and never built; that repository is deleted.

## Not doing

- **No materialised values.** A `Stream` still exposes none. A caller who
  needs the `Cancellable` keeps the `Source` and hands the stream a view.
- **Not Pekko's whole operator set.** What one consumer needed, and the
  mirror of one of them where leaving it out would look like a mistake.
- **No `runForeach`.** `runWith(Sink.foreach(...))` already says it.

## Shape

```kotlin
Stream.tick(every = pollInterval, element = Poll)                      // Stream<Nothing, Poll>
    .mapPar(1) { pollOnce() }

Stream.from(anySource)                                                 // Source<A, *> is accepted

predictions.prepend(Stream.single(current))                            // current state first
cartChanges.conflateWithSeed({ setOf(it) }) { ids, id -> ids + id }    // Stream<E, Set<CartId>>
    .mapConcat { ids -> ids }                                          // Stream<E, CartId>
```

- `Stream.Companion.from(source: Source<A, *>): Stream<Nothing, A>`; the
  materialised value is dropped, since the stream never had one to give.
- `Stream.Companion.tick(every: Duration, element: A, after: Duration = every): Stream<Nothing, A>`.
- `Stream<E, A>.prepend(first: Stream<E, A>)` and its mirror `concat(next)`,
  Pekko's names and Pekko's order.
- `conflateWithSeed(seed, aggregate)` and `mapConcat(f)` on `Pipe`, with
  `Stream`'s as `via`, as spec 0008 shaped the others; both through the
  defect guard once spec 0010 lands, and through a plain catch until then.

## Why this shape

Each is one line over the Pekko operator of the same name, and the value is
that the line is inside the typed world rather than outside it. The
alternative — leave them on the `Source` side and document the wrap — is
what the consumer did, and it is the unwrapped stretch the problem names.

## Stack

- [x] **`spec-0011-seams`** ([#9](https://github.com/matthewjones372/lark/pull/9)) — `from(Source<A, *>)`, `tick`, `prepend`,
      `concat`, `conflateWithSeed`, `mapConcat`, the `docs/stream.md` rows.
      Done when: a `Source.tick` becomes a `Stream` with no
      `mapMaterializedValue` in sight, and a throw inside `mapConcat` dies.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Drop the materialised value in `from(Source<A, *>)`, or refuse anything
   but `NotUsed` and add `tick` alone?** Recommend drop: the type says the
   stream has none, and refusing pushes every hot `Source` back to the wrap.
2. **`concat` too, or only the `prepend` that was needed?** Recommend both.
3. **Build this before or after 0010?** Recommend after, so the two new
   operators are born inside the guard rather than retrofitted.

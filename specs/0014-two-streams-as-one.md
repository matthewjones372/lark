# 0014 — Two streams as one

## Problem

Specs 0011 and 0012 left fan-in out, so `concat`, `prepend` and `orElse` are
the whole of it: three operators that all put one stream *after* another.
Nothing runs two at once. A service reading orders from an HTTP poll and a
Kafka topic, or pairing each order with a price, drops to `toSource()` — and
for a stream carrying an `E` that means `either()` first, so both branches go
back to an `Either` in every element.

The raw API charges twice for that. Pekko's `merge` and `zip` want both sides
at exactly one element type, so two feeds whose failures differ have to be
widened by hand — a `mapLeft { it as FeedError }` per branch that does nothing
at runtime and exists only to make the call type-check. Then `zip` answers
with `org.apache.pekko.japi.Pair`, which has `first()` and `second()` and no
`component1`, so the stage after it destructures nothing and unwraps two
`Either`s to get at a value.

## Not doing

- **No `mergeLatest`, `mergePreferred`, `mergePrioritized`, `zipAll`,
  `zipLatest`.** Each is a policy nobody has asked for; `toSource()` is the
  door until one of them is named.
- **No `GraphDSL` and no `Source.combine`.** A fan-in with more than one
  output, or a materialised value, is what `toSource()` and `toFlow()` exist
  for.
- **No materialised value.** As everywhere: a `Stream` has none.
- **No change to `concat`, `prepend` or `orElse`.** They already read.

## Shape

```kotlin
val http: Stream<HttpDown, Order> = ...
val kafka: Stream<KafkaLag, Order> = ...
val prices: Stream<HttpDown, Price> = ...

val priced: Stream<FeedError, Priced> =
    http.merge(kafka)                                       // FeedError, inferred
        .zipWith(prices) { order, price -> Priced(order, price) }
```

- `Stream<E, A>.merge(other: Stream<E2, A>): Stream<E, A>` for `E2 : E`, and
  `mergeAll(vararg others)`. A failure on either side ends the run, as
  `concat`'s already does.
- `Stream<E, A>.interleave(other: Stream<E2, A>, segmentSize: Int)`.
- `zipWith(other: Stream<E2, B>, f: (A, B) -> C): Stream<E, C>`, `f` through
  the spec 0010 guard, and `zip(other): Stream<E, Pair<A, B>>` as
  `zipWith(other, ::Pair)` — Kotlin's `Pair`, which destructures.
- On `Stream` only, as `concat` and `prepend` are; no `Pipe` forms yet.
- Docs: a **Two feeds as one** pair in `docs/stream.md`'s before/after
  section, compiled by `ReadmeExampleTest`'s mechanism like the others.

## Why this shape

The widening is the whole argument. `Stream` is covariant in `E`, so a
`Stream<HttpDown, Order>` already *is* a `Stream<FeedError, Order>` and
`E2 : E` lets the call site name the wider failure once — where raw Pekko
makes each branch carry a `mapLeft` that is a no-op with a cast in it. The
same covariance is why the merged element type stays `Order` rather than
`Either<FeedError, Order>`: there is no `Left` riding along to be folded out
three stages later.

`zipWith` before `zip` because the pair is usually not what anyone wanted —
`japi.Pair` is named in `docs/stream.md`'s opening complaint, and a `zip` that
answers with Kotlin's `Pair` is worth having only because the next `map` can
destructure it.

## Stack

- [x] **`spec-0014-merge`** — `merge`, `mergeAll`, `interleave`, the docs
      rows. Done when: two feeds with different failure types merge under
      their common supertype with no `mapLeft` written, and a failure on
      either side ends the run.
- [x] **`spec-0014-zip`** — `zipWith` and `zip`, the before/after pair.
      Done when: a zipped element destructures, and a throw in a `zipWith`
      body dies naming `zipWith` and the build site.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Does Kotlin infer the common `E` from two receivers, or does the call
   site have to name it?** `merge` needs `E` to widen to the supertype of
   both branches, which is the inference that bit `mapOrFail` and that spec
   0012 asks about too. Recommend a compile test that pins the answer rather
   than a guess — and if it needs naming, `merge<FeedError>(kafka)` is a far
   cheaper spelling than two `mapLeft`s.
2. **`Pipe` forms too?** Spec 0008 says every operator lives on `Pipe` with
   `Stream`'s as `via`, but `concat` and `prepend` already break that, and a
   `Flow.merge` is only reusable if the merged-in stream is fixed at build
   time. Recommend `Stream` only, matching the family it joins.
3. **`zip` at all, or `zipWith` alone?** Recommend both: a `Pair` that
   destructures is the one tuple Kotlin readers accept.
4. **Pekko's `eagerComplete` on `merge`?** Recommend leaving it off — the
   default waits for both, and a flag nobody has asked for is a policy in the
   signature.
5. **Is `interleave` reached for, or is it here because Pekko has it?**
   Recommend cutting it unless someone names it, as spec 0013's first open
   question cuts `sliding`.

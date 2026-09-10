# 0012 — An element that is itself a stream

## Problem

The repository still on raw Pekko writes this by hand:

```kotlin
fun <L, A, B> Source<Either<L, A>, NotUsed>.flatMapConcatEither(
    f: (A) -> Source<B, NotUsed>,
): Source<Either<L, B>, NotUsed>
```

Its whole body is the three moves the failure-in-the-element shape forces:
unwrap the `Right`, apply `f`, re-wrap as `Either<L, B>`, and carry every
`Left` past a stage that never wanted it. `Stream<E, A>` deletes that body —
the failure is on Pekko's channel, so there is no `Left` to pass through and
no `L` to widen — but there is no `flatMapConcat` here to delete it with.
`mapConcat` (spec 0011) takes an `Iterable`: elements already in hand, not an
element that expands into another stream, which is what a paged fetch or a
per-element sub-query is.

The escape hatch is shut. `toSource()` is declared on `Stream<Nothing, A>`, so
a stream carrying an `E` has to `either()` before it can leave — which hands
back a `Source<Either<E, A>, NotUsed>`, exactly the shape the helper exists
for. Reaching for flattening in lark today means writing that helper again,
over a stretch with no defect guard.

## Not doing

- **No `groupBy`, `splitWhen`, `splitAfter`, `prefixAndTail`.** They answer
  with a stream of streams over Pekko's `SubSource`, whose typed spelling is
  a design of its own and not this one.
- **No `switchMap` or `flatMapPrefix`.** Nobody has reached for them.
- **No fan-in.** `merge`, `zip` and `interleave` join two failure types rather
  than nest one; they belong with the rest of the long tail.
- **No materialised value.** A `Stream` still has none, so the inner one's is
  dropped as `Stream.from(source)` drops it.

## Shape

```kotlin
fun fetchPage(cursor: Cursor): Stream<FetchError, Page>

val pages: Stream<FetchError, Page> =
    Stream.from(cursors)                                 // Stream<Nothing, Cursor>
        .flatMapConcat { cursor -> fetchPage(cursor) }   // Stream<FetchError, Page>
        .flatMapMerge(breadth = 4) { page -> items(page) }

val nested: Stream<FetchError, Stream<FetchError, Page>> = ...
nested.flatten()                                         // Stream<FetchError, Page>
```

- `Stream<E, A>.flatMapConcat(f: (A) -> Stream<E2, B>): Stream<E, B>` for
  `E2 : E`, the widening `via` already has; the inner stream's failure is the
  outer's, and a `Stream<Nothing, A>` receiver widens to it by covariance.
- `flatMapMerge(breadth: Int, f: (A) -> Stream<E2, B>)`, the unordered twin.
  Leaving it out would read as the mistake `concat` beside `prepend` avoided.
- `Stream<E, Stream<E2, B>>.flatten(): Stream<E, B>`, which is
  `flatMapConcat { it }` under the name a reader looks for first — and the
  form a stream built by `map` into a fetch already has.
- On `Pipe` first, `Stream`'s as `via` of them, as spec 0008 shaped the rest.
- `f` is caller code, so it runs through the spec 0010 guard: a throw while
  *building* the inner stream dies naming `flatMapConcat` and the build site.
- Docs: three rows under **Element by element** in `docs/stream.md`, and the
  `flatMapConcatEither` pair in **Before and after** — the raw helper beside
  the call that replaces it, compiled by `ReadmeExampleTest`'s mechanism.

## Why this shape

The inner stream is a `Stream` rather than a `Source` because the point is
that it may fail: `f` returning a `Source<B, NotUsed>` would push a fetch that
can fail back to the untyped side, which is the problem restated. `E2 : E`
rather than a fresh `E2` unioned in, because Kotlin has no union type and
every other seam here — `via`, `absolve` — already asks the call site to
name the wider failure once.

## Stack

- [ ] **`spec-0012-flat-map-concat`** — `flatMapConcat` and `flatten` on
      `Pipe`, `Stream`'s as `via`, through the guard, the docs rows and the
      before/after pair. Done when: a stream of cursors becomes a stream of
      pages with no `toSource` in sight, and a throw in the body dies naming
      `flatMapConcat`.
- [ ] **`spec-0012-flat-map-merge`** — `flatMapMerge(breadth, f)`, the same
      shape unordered. Done when: a `breadth` of 4 has four inner streams
      running and the elements interleave.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Does `E2 : E` infer from a `Stream<Nothing, A>` receiver, or does this
   need `mapOrFail`'s declaring pair?** `via` gets there by covariance, but
   `E2` is read out of a lambda's return type here. Recommend the single
   signature, with a `DoesNotCompileTest` case deciding it rather than a
   guess; add the `@JvmName` pair only if inference actually fails.
2. **Which operator does a defect *inside* the inner stream name?**
   Recommend the inner one, untouched — it built the stage that threw, and
   naming `flatMapConcat` too would bury the line the caller needs.
3. **A merged `flatten` too?** Recommend no: `flatMapMerge { it }` already
   says it, and a `flattenMerge(breadth)` is a third name for one operator.
4. **Should there be a form that maps the inner failure into the outer's**
   instead of requiring `E2 : E`? Recommend no: `mapError` in the long-tail
   spec does that in one place rather than in every nesting operator.

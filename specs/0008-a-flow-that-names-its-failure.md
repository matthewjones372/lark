# 0008 — A flow that names its failure

## Problem

`lark-stream` types the source side only. `Stream<E, A>` wraps a
`Source<A, NotUsed>` and every operator is written on it, so a reusable
middle piece — Pekko's `Flow<In, Out, Mat>`, the thing a team `via`s into
several sources — has no typed spelling. Pekko's `divertTo` lives on both
`Source` and `Flow`; dipper's `divertLefts` lives on `Stream` alone, so the
`Either` split cannot be packaged and reused. Leaving the typed world to
write one costs the failure type: `toSource()` is declared on
`Stream<Nothing, A>`.

## Not doing

- **No typed `Sink`.** A sink does not fail with `E`; `divertLefts` and
  `runWith` keep taking Pekko's.
- **No materialised value on a pipe.** `NotUsed`, as `Stream` has.
- **No graph DSL, no fan-in.** `toFlow()` is the door to those.
- **Not the name `Flow`.** It is the thing being wrapped, and every Kotlin
  reader also hears kotlinx.

## Shape

```kotlin
val settle: Pipe<IngestError, Row, Receipt> =
    Pipe.mapOrFail<IngestError, Row, Customer> { row -> Customer(row.id, row.customer ?: raise(NoCustomer(row.id))) }
        .mapPar(4) { customer -> ledger.settle(customer).await() }
        .divertLefts(to = declinedSink)                              // divertTo, on the flow

Stream.from(rows).via(settle).runWith(receiptSink).run(system)       // reused per source
Stream.from(kafka).via(settle).runWith(receiptSink).run(system)
```

- `class Pipe<out E, in In, out Out : Any>` over a `Flow<In, Out, NotUsed>`.
  `Pipe.from(flow)` and `Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E | E2, B>`
  (with `E2 : E` widening as `absolve` does) are the ways in;
  `Pipe<Nothing, In, Out>.toFlow()` the way out, gated on `E = Nothing`
  exactly as `toSource()` is; `Pipe.identity<A>()` to start one.
- **Every operator moves onto `Pipe`, and `Stream`'s become `via`.**
  `Stream.map(f)` is `via(Pipe.map(f))`; likewise `mapOrFail`, `mapPar`,
  `filter`, `mapAsync`, `either`, `absolve`, `divertLefts`, `catchAll`,
  `orElse`. One implementation; Pekko's own duplication between `Source` and
  `Flow` is not copied. `Pipe` also composes: `pipe.via(other)`.
- Failure travels as it does: the private wrapper on Pekko's failure channel,
  unwrapped by `run`. `Exit`, `Run`, `runWith` unchanged.
- Docs: `docs/stream.md` gains **Pipes** in the operator table and a
  **Before and after** section — the same pipeline as raw Pekko `Source`
  (throwing for a failure, `divertTo` with a predicate and two casts,
  `whenComplete` sorting one `Throwable`) beside the `Stream` form — both
  compiled by `ReadmeExampleTest`'s mechanism, the README carrying the same
  pair.

## Why this shape

A wrapper over `Flow` with the same three types `Stream` has is the smallest
thing that makes the middle reusable, and rewriting `Stream`'s operators as
`via` is what stops the library from becoming two copies of itself. The
alternative — operators on both, as Pekko has — is more code to keep agreeing
and nothing for it. `E2 : E` on `via` mirrors `absolve`, so a pipe that fails
with a narrower error slots into a wider stream without a cast.

## Stack

- [ ] **`spec-0008-pipe`** — `Pipe`, `from`, `identity`, `via` (both), `toFlow`.
      Done when: a `Pipe` built from a Pekko `Flow` runs through `Stream.via`
      and `Exit` is unchanged; `toFlow` does not compile on a `Pipe<E, …>`
      with `E` not `Nothing` (`DoesNotCompileTest`).
- [ ] **`spec-0008-operators`** — the operators on `Pipe`; `Stream`'s as `via`.
      Done when: dipper's suite passes unedited, and the Shape's `settle` is
      reused across two sources in a test.
- [ ] **`spec-0008-docs`** — the table rows, **Before and after**, README.
      Done when: both examples compile and run in `ReadmeExampleTest`, and
      the README pair equals the document's.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **`Pipe.mapOrFail<E, A, B> { }` as a starting form, or always
   `Pipe.identity<A>().mapOrFail { }`?** Recommended: both, the companion form
   being the one the Shape shows; it is the `Stream.from(rows).mapOrFail`
   twin.
2. **Variance.** `in In` on `Pipe` lets a `Pipe<E, Any, B>` take any source;
   recommended, since `Flow` is invariant only because Java is.

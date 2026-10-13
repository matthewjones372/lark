# 0043 — Two signatures a relay tripped on

## Problem

The petshop's outbox relay is the first consumer to use `mapPar` on a stream
that has not named a failure, with a body that never raises. It does not
compile:

```kotlin
Stream.tick(every = 1.seconds, element = Unit)
    .mapPar(1) { _ -> shop.unsent() }   // Cannot infer type for type parameter 'F'
```

`mapPar` is a pair
([MapPar.kt](../lark-stream/src/main/kotlin/io/github/matthewjones372/lark/stream/MapPar.kt)).
On a `Stream<Nothing, A>` the one that reads the failure out of the body wins,
because its receiver is more specific. A body that never raises gives it
nothing to read. The caller writes `mapPar<Nothing, _, _>(1)`. The common case,
a blocking call that cannot fail in a declared way, is the one that needs
annotating.

The second is smaller. `groupedWithin` and its `Pipe` forms take a
`java.time.Duration`
([Batching.kt](../lark-stream/src/main/kotlin/io/github/matthewjones372/lark/stream/Batching.kt)),
while `tick` and lark's own `timeout` take `kotlin.time.Duration`, and
`docs/stream.md` says `Duration` without saying which.

## Not doing

- **No change to `mapOrFail`.** It has the same pair, but calling `mapOrFail`
  with a body that never fails is calling the wrong operator: `map` is there.
- **No deprecation cycle for `mapPar`.** A deprecated overload still takes part
  in resolution and still wins, so it would keep the bug it is deprecated for.
  Lark is `0.x`, and this is a break in the changelog.
- **No other `java.time` sweep.** `groupedWithin` is the only public signature
  taking one.

## Shape

```kotlin
// A body that cannot fail: the stream's failure type is kept, Nothing included.
Stream.tick(every, Unit).mapPar(1) { _ -> shop.unsent() }            // Stream<Nothing, List<Event>>

// A body that raises on a stream with no failure yet: the OrFail name, as mapOrFail's.
Stream.from(rows).mapParOrFail(4) { row -> directory.lookup(row.id).bind() }   // Stream<NoCustomer, Customer>

// A stream that already declares E: either name, the body in Raise<E>.
declared.mapPar(4) { row -> directory.lookup(row.id).bind() }         // Stream<IngestError, Customer>

stream.groupedWithin(100, within = 1.seconds)                         // kotlin.time.Duration
```

- `mapPar` is one signature per form, `Raise<E>.(A) -> B` keeping the stream's
  `E`.
- `mapParOrFail` is the pair `mapPar` is today, under `mapOrFail`'s suffix.
- The same split on `Pipe`.
- `groupedWithin` takes `kotlin.time.Duration` on `Stream` and both `Pipe` forms.

## Why this shape

The two meanings are both wanted and cannot share a name: Kotlin fixes the type
variable before it looks at the body, so no signature infers `Nothing` for a
body that never raises and `F` for one that does. One of them has to be spelled
differently. The one spelled differently should be the rarer and more
deliberate one, declaring a failure from inside a blocking body. `OrFail` is
already how this library says "the body may fail". The alternative, keeping
`mapPar` as the declaring pair and adding `mapParOf` or similar for the plain
case, leaves the obvious call broken.

## Stack

- [ ] **`spec-0043-mappar-or-fail`**: `mapParOrFail` pair on `Stream` and
      `Pipe`, `mapPar` single-signature, docs table and example updated.
      Done when: a compile test holds that `Stream<Nothing, A>.mapPar` with a
      body that never raises compiles to `Stream<Nothing, B>`, and that
      `mapParOrFail` on the same stream infers `F` from a `raise`.
- [ ] **`spec-0043-grouped-within-duration`**: `groupedWithin` on
      `kotlin.time.Duration`.
      Done when: `BatchingTest` passes with a `kotlin.time.Duration`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is the break acceptable in the next minor?** Recommend yes. Every caller
   of the declaring form on `Stream<Nothing, A>` gets a compile error naming
   `raise`'s argument, and the fix is the rename.
2. **Should `groupedWithin` keep a `java.time.Duration` overload?** Recommend
   no. Two overloads differing only in duration type make every call site
   import-sensitive.

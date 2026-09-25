# 0046 — A stream described once

## Problem

A pelican endpoint is a value in `pelican-core`, which depends on no HTTP
library. `pelican-pekko` is one interpreter of it, and a backend-specific type
such as `StreamIn<T>` only becomes a Pekko `Source` at the edge
(`StreamIn.toSource()` in `pelican-pekko`).

A lark `Stream<E, A>` is the opposite. It *is* a Pekko `Source`
([Stream.kt](../lark-stream/src/main/kotlin/io/github/matthewjones372/lark/stream/Stream.kt)),
and every operator builds a Pekko stage the moment it is called. A pipeline
written against lark's operators is Pekko code, however little of Pekko it
names. 0039 wanted a stream on forks for services without Pekko. It had to
propose `Feed`, a second type with its own operators, and it rejected a second
backend because `toSource` and `Stream.from(Source)` keep Pekko in the API.

## Not doing

- **No new operators.** Every one `lark-stream` has today keeps its name and
  signature. `buffer`'s `OverflowStrategy` is the one exception (question 3).
- **No materialised values beyond today's.** `Run` still answers `Exit`, and
  `Running` still only stops.
- **No backend picked at runtime.** The interpreter is chosen where the run
  starts, and not from configuration. A lark-app node can do that choosing
  later (0028).
- **No change to pelican or the petshop** beyond a coordinate bump, made in
  their own repositories.

## Shape

```kotlin
// orders.kt — imports lark-stream only; no Pekko on its classpath
val tally: Run<BadRow, Tally> =
    Stream.from(rows)
        .mapOrFail { parse(it).bind() }
        .mapPar(8) { enrich(it) }
        .groupedWithin(100, 1.seconds)
        .runFold(Tally(0)) { t, batch -> t + batch.size }

// the edge picks the backend
tally.run(PekkoStreams(system))    // lark-stream-pekko
tally.run(Forks(executor))         // lark-stream-forks: virtual threads and 0038's channels
```

- `lark-stream` depends on `lark` alone. `Stream`, `Pipe` and `Run` become
  sealed trees of nodes: `FromIterable`, `Map`, `MapPar`, `GroupedWithin`,
  `Collect`, `Fold` and so on. Operators allocate nodes and nothing else.
- `interface StreamBackend { fun <E, R : Any> start(run: Run<E, R>): Running<E, R> }`.
  `run` and `start` take a backend where they take a system today.
- A Pekko type enters or leaves only through `lark-stream-pekko`:
  `Stream.from(Source)`, `toSource()`, `runWith(Sink)`, `alsoTo(Sink)`. Each is
  a `Native` node. It holds the Pekko value, and only that backend reads it.
- The Pekko interpreter compiles the tree into the `Source` that today's code
  builds directly, with the same guards, `DeclaredFailure` and kill switch.

## Why this shape

This is the initial encoding that pelican's endpoints already use. Describing
the stream as data costs one tree walk per materialisation. In return, a
pipeline is testable and reusable without an actor system, and a service can
change backend without touching its pipelines.

0039's objection is answered by moving the Pekko edges out of the core rather
than keeping them in it. The alternative is a tagless `Stream<B, E, A>` with a
backend type parameter. That catches a `Native` node reaching the wrong backend
at compile time, but every signature in the module gains a third parameter.
Recommended against. The check runs instead in `start`, which walks the tree
before anything materialises and refuses the run with a defect naming the
builder and where it was built (0010's build site).

## Stack

- [ ] **`spec-0046-tree`**: nodes for sources, `map`/`mapOrFail`/`filter`, `Collect`, `Fold`,
      and an internal Pekko compiler. `Stream` keeps its public API.
      Done when: `StreamTest`, `SourcesTest` and `RaiseInStreamTest` pass unchanged.
- [ ] **`spec-0046-operators`**: the remaining operators become nodes, one PR per file group
      if a PR goes past 200 lines. Done when: the whole existing suite passes unchanged.
- [ ] **`spec-0046-split`**: `lark-stream-pekko`, `StreamBackend`, the `Native` edges moved,
      and `NoOtherDependenciesTest` saying `lark-stream` is `lark` and Arrow only.
      Done when: a `Native` node started on another backend is `Died` before any element flows.
- [ ] **`spec-0046-forks`**: `lark-stream-forks`, taking over 0039's `spec-0039-feed`
      entries as an interpreter. Done when: a raise in `map` is `Failed`, and `take(3)` on an
      infinite stream ends.
- [ ] **`spec-0046-parity`**: the operator suites parameterised over both backends, as
      pelican's `allBackends` is. Done when: the suite runs twice, and an operator a backend
      cannot run is refused by name, not skipped.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Coordinates.** (a) `lark-stream` becomes the core, and Pekko users add
   `lark-stream-pekko`. (b) A new `lark-stream-core`, and `lark-stream` stays
   Pekko. Recommended: (a). The artifact is 0.x, and the core should have the
   plain name.
2. **An operator only one backend can run.** Refuse it in `start` (as above), or
   leave it out of the core and put it in that backend's module. Recommended:
   leave it out of the core. `conflateWithSeed` and `wireTap` are the likely
   candidates.
3. **`buffer(size, OverflowStrategy)`.** Pekko's enum can't stay in the core.
   Recommended: lark's own `Overflow { Backpressure, DropHead, DropTail, Fail }`,
   with the Pekko module mapping it.
4. **0039.** Recommended: supersede it by this spec. `Feed` is no longer a
   separate type, and its `mapPar`/`buffer` entry becomes the second half of
   `spec-0046-forks`.

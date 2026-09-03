# 0001 — A stream that names its failure

*Dipper's spec 0001, imported with the code it describes; the module it
names is `lark-stream` here.*

## Problem

A Kotlin service on Pekko Streams writes the `javadsl`: `japi.Pair`,
`NotUsed`, a `Supervision.Decider` set as an attribute far from the operator
it governs, and one error channel typed `Throwable` for the whole graph.
Teams that model failure as an Arrow `Either` put one in every element, split
it with `divertTo(sink, predicate)`, and cast the survivors back.

Two things drop an element without a trace, and both are documented: a
`mapAsync` whose `CompletionStage` completes with `null` "is not passed
downstream", and a `Resume` decider means "the element is dropped and the
stream continues". Kotlin makes the first trivial — a nullable lookup lifted
into a future — and nothing in the types says so. A null was swallowed in
production this way and left no evidence of where.

## Not doing

- **No second engine.** Every operator delegates to Pekko; `toSource()` and
  `Stream.from(source)` are the escape hatch, so a missing operator is one
  line away. No graph DSL, no materialised values beyond `NotUsed`.
- **No supervision surface.** There is no `resume`. Dropping an element is
  written as `divertLefts(to = sink)`, with the sink named.
- **No coroutines.** `run` answers a `CompletionStage`; a `Flow` bridge waits
  for a use to demand it.
- **No HTTP.** A bridge to a web framework is that framework's concern.

## Shape

One module: the Kotlin standard library, `pekko-stream` and `arrow-core`,
asserted by a classpath test in the Kestrel manner.

```kotlin
val settled: CompletionStage<Exit<IngestError, Int>> =
    Stream.from(rows)                                                // Stream<Nothing, Row>
        .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) } // Stream<NoCustomer, Customer>
        .mapAsync(4) { customer -> ledger.settle(customer) }           // CompletionStage<Either<Declined, Receipt>>
        .divertLefts(to = declinedSink)                                // Stream<NoCustomer, Receipt>
        .runFold(0) { n, _ -> n + 1 }
        .run(system)
```

- `Stream<out E, out A : Any>`. The bound is the fix: `map { it.customer }` on
  a nullable field does not compile; `mapOrFail` names what a missing one means.
- `Exit<E, A>` is `Done(value)`, `Failed(error: E)` or `Died(cause: Throwable)`.
  `run` completes normally with all three; a defect is matched on, not a
  failed future nobody read.
- `fail(e)` ends the stream with `E`, carried on Pekko's failure channel in a
  private wrapper that only `run` unwraps, so fusion is untouched.
- `mapAsync` turns a `null` completion into `Died(NullPointerException)`.
- `either(): Stream<Nothing, Either<E, A>>`, `absolve()` back, and
  `divertLefts(to: Sink<L, *>)` over `Stream<E, Either<L, A>>` — the
  `divertTo` idiom with no predicate and no cast.
- `catchAll` and `orElse` leave a failure type. `Stream<Nothing, T>.toSource()`
  is the only way out to Pekko, so a stream still carrying an `E` cannot reach
  a sink until something says what a failure means.

## Why this shape

A typed view over `Source` keeps every Pekko guarantee and asks the compiler
to hold two lines: an element is never null, and a failure is a named value
until someone handles it. The alternative is a fresh engine over coroutines —
more Kotlin, but a second runtime beside the one the service runs, and
Pekko's operators to rewrite. Arrow's `Either` rather than a type of its own:
the codebases this is for already hold their failures in it, and Kotlin has
no other.

## Stack

- [x] **`spec-0001-stream`** ([#2](https://github.com/matthewjones372/dipper/pull/2)) — module, `Stream`, `Exit`, `from`, `map`,
      `mapOrFail`, `filter`, `runFold`, `runCollect`, `run`, `toSource`.
      Done when: a nullable `mapOrFail` body is in a does-not-compile test and
      `fail(e)` arrives at `run` as `Exit.Failed(e)`.
- [x] **`spec-0001-async-and-split`** ([#3](https://github.com/matthewjones372/dipper/pull/3)) — `mapAsync`, `either`, `absolve`,
      `divertLefts`, `catchAll`, `orElse`.
      Done when: a `null` completion yields `Exit.Died`, and every `Left`
      through `divertLefts` is counted at the sink.
- [x] **`spec-0001-docs`** ([#4](https://github.com/matthewjones372/dipper/pull/4)) — README with the sketch above and its imports,
      and the classpath test's claim written down.
      Done when: the README example compiles as a test.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **The name.** A dipper is the bird that walks the bed of fast streams.
   Recommend `dipper`, beside `kestrel`.
2. **`arrow-core` in the one module, or a stdlib-only core with an `arrow`
   leaf?** Recommend the one module: the split operators are the library's
   reason to exist, and every one of them is written in `Either`.
3. **`Stream` collides with `java.util.stream.Stream` at an import.**
   Recommend keeping it; `Flow` collides with two libraries.
4. **`Died` or a failed stage for defects?** Recommend `Died`: the swallowed
   null is the case for a defect being a value the caller must match.
5. **Which of the family's build gates come across?** Recommend detekt, spotless,
   Kover and the classpath test, with `AGENTS.md` and `specs/README.md`
   copied as they are.

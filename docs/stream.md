# lark-stream

**A stream that names its failure.** A typed Kotlin view over Pekko Streams:
`Stream<E, A>` carries the failure type a pipeline can end with, the element
type can never be null, and running it answers an `Exit` that is `Done`,
`Failed(e)` or `Died(cause)` — never a dropped element and never a failed
future nobody read.

A stream is a description: the operators build it, and a backend runs it.
`lark-stream-pekko` is the backend for Pekko Streams, and every operator
compiles to the Pekko stage it names. `toSource()` and `Stream.from(source)`
are the way in and out, so nothing Pekko can do is out of reach.

A service depends on `lark-stream-pekko`, and `lark-stream`, the description
with no backend in it, comes with it. Everything below is in
`io.github.matthewjones372.lark.stream`, whichever of the two it ships in. It was its own library, dipper, until
lark's [spec 0005](../specs/0005-dipper-comes-home.md) brought it here.

## The problem

A Kotlin service on Pekko Streams writes the `javadsl`: `japi.Pair`, `NotUsed`,
one error channel typed `Throwable` for the whole graph, and a
`Supervision.Decider` set as an attribute some distance from the operator it
governs. A team that keeps its failures in an Arrow `Either` puts one in every
element, splits the stream with `divertTo(sink, predicate)` and casts the
survivors back. Two things then drop an element and leave nothing behind, and
Pekko documents both: a `mapAsync` whose `CompletionStage` completes with
`null` "is not passed downstream", and a `Resume` decider means "the element is
dropped and the stream continues". The first is the easiest thing in Kotlin to
write — a nullable lookup lifted into a future — and nothing in the types says
so.

## The same pipeline here

```kotlin
// build.gradle.kts
dependencies {
    // lark-stream, Pekko Streams, lark, lark-pekko and arrow-core arrive with it; nothing else does.
    implementation("io.github.matthewjones372:lark-stream-pekko:0.5.0")
}
```

A commit after the latest tag builds the next patch version as a `-SNAPSHOT`,
which is what `./gradlew publishToMavenLocal` installs.

Rows in, receipts to one sink, declines to another, and every import it takes:

<!-- readme-example -->
```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.awaitExit
import io.github.matthewjones372.lark.stream.divertLefts
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapAsync
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runWith
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val system: ActorSystem = ActorSystem.create("ingest")
val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))

// Where a decline goes and where a receipt goes: two sinks with names, rather than a decider that drops one.
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
val receiptSink = Sink.foreach<Receipt> { receipt -> println("receipt ${receipt.id}") }

val settled: Either<IngestError, Done> = either {
    awaitExit(
        Stream.from(rows)                                              // Stream<Nothing, Row>
            .mapOrFail { row ->                                        // Stream<IngestError, Customer>
                Customer(row.id, row.customer ?: raise(NoCustomer(row.id)))
            }
            .mapAsync(4) { customer -> ledger.settle(customer) }        // up to four stages at once, in order
            .divertLefts(to = declinedSink)                            // Stream<IngestError, Receipt>
            .runWith(receiptSink)                                      // Run<IngestError, Done>
            .run(system),                                              // CompletionStage<Exit<IngestError, Done>>
    )                                                                  // Done → the value, Failed → raise, Died → throw
}
```

`Row.customer` is nullable, so the `map` that reaches for it has nowhere to go:

```
Stream.from(rows).map { row -> row.customer }

e: Return type mismatch: expected 'Any', actual 'String?'.
```

The element bound is `A : Any`, and `mapOrFail` is where a missing customer
gets a name instead of a `null`. `toSource()` is the other half: it is declared
on `Stream<Nothing, A>` alone, so a stream still carrying an `E` has no
`toSource` to call until `catchAll`, `either` or `orElse` has said what a
failure means. `DoesNotCompileTest` compiles both of those on every build and
asserts the compiler's own words, so a message invented on this page would fail
rather than persuade.

## Before and after

The same ingest twice, from the fixtures and the imports the two need between
them:

<!-- example-fixtures -->
```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.divertLefts
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapAsync
import io.github.matthewjones372.lark.stream.mapOrFail
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
```

Before — raw Pekko `Source`:

<!-- before-example -->
```kotlin
val receipts: Source<Either<IngestError, Receipt>, NotUsed> =
    Source.from(rows)
        .map { row ->                                        // Source<Either<IngestError, Customer>, NotUsed>
            either<IngestError, Customer> { Customer(row.id, row.customer ?: raise(NoCustomer(row.id))) }
        }
        .mapAsync(4) { customer ->
            customer.fold(
                { e -> CompletableFuture.completedFuture<Either<IngestError, Receipt>>(Either.Left(e)) },
                { c -> ledger.settle(c).thenApply { it.mapLeft { d -> d as IngestError } } },
            )                                                // the Left carried past a stage that never wanted it,
        }                                                    // and Declined widened to IngestError by hand
        .divertTo(
            declinedSink.contramap<Either<IngestError, Receipt>> { (it as Either.Left).value as Declined },
            { it is Either.Left && it.value is Declined },    // a cast for the sink, a predicate on the subtype
        )                                                    // and a NoCustomer Left is still in the stream
```

After — ours:

<!-- after-example -->
```kotlin
val receipts: Stream<IngestError, Receipt> =
    Stream.from(rows)
        .mapOrFail { row -> Customer(row.id, row.customer ?: raise(NoCustomer(row.id))) }   // a nullable body does not compile
        .mapAsync(4) { customer -> ledger.settle(customer) }                                // the same mapAsync, E already on the stream
        .divertLefts(to = declinedSink)                                                     // no predicate, no cast
```

Raw Pekko carries the failure in the element, so every stage after the first
unwraps and re-wraps one: the `mapAsync` that only wanted a customer carries a
`Left` past itself, and `Declined` is widened to `IngestError` by hand. A
diverted element and a failed pipeline are then the same thing in one channel,
told apart by a predicate on the error's subtype — so the `NoCustomer` that
should have ended the run keeps flowing to the consumer instead, and the
element type at the end is still `Either<IngestError, Receipt>`, which the
consumer folds too.

The second says it in the types instead. The failure is on the stream rather
than in the element, so `mapAsync` sees a `Customer` and hands on the ledger's
own `Either`, `divertLefts` splits on that rather than on a predicate, and what
is left at the end is a `Receipt`. Pekko runs the same three stages either way:
what changes is what the type says, and what it will not let you write.

## The surface

| Operator | Answers |
|---|---|
| **Building** | |
| `Stream.from(elements: Iterable<A>): Stream<Nothing, A>` | a stream of what is already in hand |
| `Stream.from(source: Source<A, *>): Stream<Nothing, A>` | the way in from Pekko, whatever the source materialises: the value is dropped, since a stream has none to give |
| `Stream.fail(error: E): Stream<E, Nothing>` | a stream that ends with the failure it names |
| `Stream.empty(): Stream<Nothing, Nothing>` | no elements and no failure |
| `Stream.single(element: A): Stream<Nothing, A>` | the one element named; `A : Any`, so a nullable does not compile |
| `Stream.of(vararg elements: A): Stream<Nothing, A>` | the elements named, in order; with none of them, `empty()` |
| `Stream.tick(every: Duration, element: A, after: Duration = every): Stream<Nothing, A>` | `element` every `every`, the first one `after` the run starts; `kotlin.time.Duration`, as lark's own `timeout` takes |
| `Stream.fromStage(stage: CompletionStage<A>): Stream<Nothing, A>` | the stage's value as one element; a `null` completion is `Died(NullPointerException)`, never `Done` with nothing |
| `Stream.fromStage(stage: CompletionStage<A>, ifNull: E): Stream<E, A>` | the same, with the absence named: a `null` completion is `Failed(ifNull)` |
| `Stream.blocking(open, next, wake, close): Stream<Nothing, A>` | a resource opened per run and read by blocking, on any backend: `next` answering `null` ends it, `stop()` calls `wake` to reach a `next` that is blocked, and `close` runs once however the run ended. Pekko reads it on its blocking-IO dispatcher |
| `Stream.hooked(source: (RunHooks) -> Source<A, *>): Stream<Nothing, A>` | opt-in (`@SourceSeam`), for a module adding a source of its own: each run hands the source hooks, so it can drain on `stop()` and clean up once the run has ended. `lark-kafka` is built on it |
| `Stream<E, A>.orFailIfEmpty(error: E2): Stream<E2, A>` | a stream that emitted nothing fails with `error`, for `E : E2`; one that emitted is untouched |
| **Element by element** | |
| `Stream<E, A>.map(f: (A) -> B): Stream<E, B>` | `B` is bound to `Any`, so a nullable body does not compile |
| `Stream<E, A>.mapOrFail(f: Failing<E>.(A) -> B): Stream<E, B>` | as `map`, with the body in a `Raise<E>`: `fail(e)`, `raise`, `bind` and `ensure` |
| `Stream<E, A>.filter(predicate: (A) -> Boolean): Stream<E, A>` | the elements that match, the only place one is dropped on purpose |
| `Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B>` | a body that answers a stage: up to `parallelism` at once, in the input's order; a `null` completion dies |
| `Stream<E, A>.mapPar(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, B>` | a body that must block: one virtual thread per element in flight, in a `Raise<E>`; `B : Any`, so no completion drops an element |
| `Stream<E, A>.mapPar(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Stream<E, B>` | the same, with every body run on the executor it names |
| `Stream<E, A>.mapParOrFail(parallelism: Int, f: Raise<F>.(A) -> B): Stream<F, B>` | `mapPar` with the failure read out of the body, on a stream that has not named one; `mapOrFail`'s suffix, and `on` as above |
| `Stream<E, A>.mapConcat(f: (A) -> Iterable<B>): Stream<E, B>` | each element's own elements, in its order; one that answers with none emits none |
| `Stream<E, A>.conflateWithSeed(seed: (A) -> S, aggregate: (S, A) -> S): Stream<E, S>` | a backlog collapsed while downstream is busy: `seed` starts the aggregate and `aggregate` folds each later element in |
| `Stream<E, A>.filterNot(predicate: (A) -> Boolean): Stream<E, A>` | `filter`'s mirror, Pekko's own name |
| `Stream<E, A>.flatMapConcat(f: (A) -> Stream<E2, B>): Stream<E, B>` | each element expanded into a stream of its own, one after another, for `E2 : E`; the inner stream may fail |
| `Stream<E, A>.flatMapMerge(breadth: Int, f: (A) -> Stream<E2, B>): Stream<E, B>` | the same with up to `breadth` inner streams running, their elements interleaved |
| `Stream<E, Stream<E2, B>>.flatten(): Stream<E, B>` | `flatMapConcat { it }`, under the name a stream built by `map` into a fetch is looked up by |
| `Stream<E, A>.scan(zero: S, f: (S, A) -> S): Stream<E, S>` | `zero` first, then what `f` carried after each element |
| `Stream<E, A>.statefulMap(create: () -> S, f: (S, A) -> Pair<S, B>, onComplete: (S) -> B?): Stream<E, B>` | state carried across elements as a Kotlin `Pair`; `onComplete` is the one last element the state may still owe |
| **Two streams as one** | |
| `Stream<E, A>.prepend(first: Stream<E, A>): Stream<E, A>` | `first`'s elements before this stream's, Pekko's name and Pekko's order |
| `Stream<E, A>.concat(next: Stream<E, A>): Stream<E, A>` | `next`'s elements after this stream's; both operands materialise at once, so a failure in either ends the run |
| `Stream<E, A>.take(n: Long)` / `.drop(n: Long)` | the first `n`, or everything after them |
| `Stream<E, A>.takeWhile(predicate)` / `.dropWhile(predicate)` | up to where the predicate first answers false, or from it, that element included |
| `Stream<E, A>.grouped(n: Int): Stream<E, List<A>>` | batches of `n`, the last one short; a read-only `List`, not the `java.util.List` Pekko hands back |
| `Stream<E, A>.sliding(n: Int, step: Int = 1): Stream<E, List<A>>` | a window of `n`, moved on by `step` |
| `Stream<E, A>.groupedWithin(n: Int, within: Duration): Stream<E, List<A>>` | batches of at most `n` and never later than `within`, so a quiet feed still answers |
| `Stream<E, A>.buffer(size: Int, strategy: OverflowStrategy): Stream<E, A>` | room between a fast producer and a slow consumer, and what to do when it fills |
| `Stream<E, A>.alsoTo(to: Sink<A, *>)` / `.wireTap(to: Sink<A, *>)` | every element to `to` as well; `alsoTo`'s backpressure is the pipeline's, `wireTap`'s is dropped from |
| `Stream<E, A>.merge(other: Stream<E2, A>): Stream<E, A>` | both feeds as one, in whatever order they arrive, for `E2 : E` |
| `Stream<E, A>.mergeAll(vararg others: Stream<E2, A>): Stream<E, A>` | `merge` over as many as there are |
| `Stream<E, A>.interleave(other: Stream<E2, A>, segmentSize: Int): Stream<E, A>` | `segmentSize` elements from each in turn |
| `Stream<E, A>.zipWith(other: Stream<E2, B>, f: (A, B) -> C): Stream<E, C>` | one element from each, combined; ends when either side does |
| `Stream<E, A>.zip(other: Stream<E2, B>): Stream<E, Pair<A, B>>` | `zipWith(other, ::Pair)`, into Kotlin's `Pair`, which destructures |
| **The split in `Either`** | |
| `Stream<E, A>.either(): Stream<Nothing, Either<E, A>>` | the failure as the last element, leaving none in the type |
| `Stream<E, Either<L, R>>.absolve(): Stream<E, R>` | back again: a `Left` fails the stream, for `L : E` |
| `Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R>` | every `Left` to that sink, every `Right` onwards — no predicate, no cast |
| **Leaving a failure type** | |
| `Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A>` | the declared failure handled, and a defect still dying |
| `Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A>` | `other` on a failure, not on an empty stream |
| `Stream<E, A>.mapError(f: (E) -> E2): Stream<E2, A>` | the declared failure said in another vocabulary, without saying recovery |
| `Stream<E, A>.restartOnDefect(schedule: Schedule<Throwable, *>): Stream<E, A>` | on a defect, the same description run again after the schedule's delay, with a warn line each time; a declared failure passes through, and a schedule that is done lets the defect through as `Died` |
| **Running** | |
| `Stream<E, A>.runCollect(): Run<E, List<A>>` | a run described, collecting every element |
| `Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R>` | a run described, folding into `R` |
| `Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M>` | a run described, to the sink named; the sink's materialised value is the run's. `M : Any`, and a sink that materialises `null` anyway is `Died`, never `Done(null)` |
| `Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>>` | the one call that materialises, on the system it names |
| `Run<E, R>.start(system: ClassicActorSystemProvider): Running<E, R>` | the same run, with a handle: `exit` is what `run` answers, `stop()` ends it now as `Done` with what the sink has, and `close()` stops and waits, so `Running::close` is a graph node's release. A source that drains, such as a Kafka consumer, is stopped by draining, so what it already sent still reaches the sink |
| `Raise<E>.awaitExit(stage: CompletionStage<Exit<E, R>>): R` | the run waited for inside a `Raise`: `Done` is the value, `Failed` raises, `Died` throws |
| **Pipes** | |
| `Pipe.from(flow: Flow<In, Out, NotUsed>): Pipe<Nothing, In, Out>` | the way in from Pekko's `Flow` |
| `Pipe.identity(): Pipe<Nothing, A, A>` | the pipe that changes nothing, where a chain starts |
| `Pipe<E, In, Out>.via(next: Pipe<E2, Out, Out2>): Pipe<E, In, Out2>` | two pipes as one, the narrower failure slotting in for `E2 : E` |
| `Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E, B>` | the pipe spliced into the stream, for `E2 : E` |
| `Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed>` | Pekko's own `Flow`, once nothing is left to declare |
| every operator above | on `Pipe` too, in two forms: `Pipe.map(f)` starts a pipe and `pipe.map(f)` carries one on. `Stream`'s are `via` of them, so there is one implementation of each |
| **The way out** | |
| `Stream<Nothing, A>.toSource(): Source<A, NotUsed>` | Pekko's own `Source`, once nothing is left to declare |

## A body that must block

`mapAsync` is for a body that answers a `CompletionStage`. `mapPar` is for one
that cannot: a JDBC call, a blocking client, anything whose answer arrives by
returning. Every element in flight gets a virtual thread of its own, at most
`parallelism` of them at a time, and the body runs in the stream's `Raise<E>`,
so `raise`, `bind` and `parZip` are all in reach of it:

<!-- mappar-example -->
```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapParOrFail

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class NoCustomer(val id: Int)

// A lookup with no stage to hand back: it answers by returning, so reaching it means blocking.
class Directory {
    fun lookup(id: Int): Either<NoCustomer, Customer> = Either.Right(Customer(id, "ada"))
}

val directory = Directory()
val rows = listOf(Row(1, "ada"), Row(2, "grace"))

val customers: Stream<NoCustomer, Customer> =
    Stream.from(rows).mapParOrFail(4) { row -> directory.lookup(row.id).bind() }   // a virtual thread per element in flight
```

`mapPar` keeps the failure type the stream already has, `Nothing` included, so
a body that cannot fail needs nothing spelled out. On a stream that has not
named a failure yet, `mapParOrFail` reads one out of the body, as `mapOrFail`
does. The two cannot share a name, because Kotlin fixes the type before it
reads the body.

Blocking a virtual thread parks it and leaves the carrier to the next one, so
four in flight are four threads and not four platform threads — with one
exception worth knowing about on JDK 21 to 23, where a `synchronized` block
inside a driver pins the carrier for as long as it blocks; JEP 491 fixed that
in 24. A raise is the stream's declared failure, anything thrown is
`Died(cause)`, and a body still running when the stream is torn down is
interrupted where it blocked, because Pekko never cancels the stage its
`mapAsync` is waiting on.

## Missing is a failure, not an empty stream

A stage that completed with `null`, an empty `Optional`, a lookup that found
nothing: every builder that reads absence as emptiness makes a source with no
elements out of a miss, and a run over one answers `Done` having processed
nothing — which is what a run that had nothing to process answers too. Nothing
in the types said the start was a lookup that could miss, and nothing at
runtime said that zero elements was the wrong answer.

<!-- missing-example -->
```kotlin
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.fail
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.fromStage
import io.github.matthewjones372.lark.stream.orFailIfEmpty
import io.github.matthewjones372.lark.stream.single
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Customer(val id: Int, val name: String)

data class Missing(val id: Int)

// A directory that answers with a customer or with nothing at all, which is the shape of every lookup.
fun lookup(id: Int): Customer? = if (id == 1) Customer(1, "ada") else null

val id = 1
val stage: CompletionStage<Customer> = CompletableFuture.completedFuture(Customer(id, "ada"))
val rows: Source<Customer, NotUsed> = Source.empty()                    // a source lark did not build

val one: Stream<Missing, Customer> =
    lookup(id)?.let { Stream.single(it) } ?: Stream.fail(Missing(id))   // absence is a failure with a name

val everything: Stream<Missing, Customer> =
    Stream.from(rows).orFailIfEmpty(Missing(id))                        // zero elements → Failed(Missing(id))

val answered: Stream<Nothing, Customer> = Stream.fromStage(stage)       // a null completion → Died(NullPointerException)

val declared: Stream<Missing, Customer> =
    Stream.fromStage(stage, ifNull = Missing(id))                       // a null completion → Failed(Missing(id))
```

`single` and `of` take `A : Any`, so the nullable is refused where the value
is and the `?:` naming the failure is written at the lookup rather than
somewhere downstream. `fromStage` checks at runtime, because a stage from Java
can complete with `null` whatever its type argument says — the first form dies
with a `NullPointerException` naming the builder, the second fails with the
error given. `orFailIfEmpty` is the word a caller says over a source lark did
not build: one built elsewhere is opaque, so zero elements is all there is to
go on, and saying it is a decision rather than a default. There is no
`fromNullable`: the builder that turns absence into emptiness is the mistake.

The one thing a library cannot reach is code that never enters it. detekt's
`ForbiddenMethodCall` can, with type resolution on — `detektMain` rather than
`detekt`. This is the list this repository holds itself to, in
[`config/detekt/detekt.yml`](../config/detekt/detekt.yml); paste it into
yours:

```yaml
ForbiddenMethodCall:
  active: true
  methods:
    - reason: 'A stage that completes with null emits nothing: Stream.fromStage names the absence.'
      value: 'org.apache.pekko.stream.javadsl.Source.completionStage'
    - reason: 'The older name for the same builder, and the same null completion.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromCompletionStage'
    - reason: 'The Scala half of it: a Future completed with null emits nothing either.'
      value: 'org.apache.pekko.stream.javadsl.Source.future'
    - reason: 'The older name for the Future form, with the same completion.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromFuture'
    - reason: 'A java.util.stream over ofNullable or an empty Optional is a source of no elements.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromJavaStream'
    - reason: 'An iterator over an empty Optional is a source of no elements.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromIterator'
```

The name on its own is the match, so every overload of it is covered. Pekko's
javadsl has no `Source.from(Optional)` to name — an `Optional` reaches a source
through `fromJavaStream` over `Optional.stream()` or through `fromIterator`,
which is what those two entries are for.

## How a failure travels

`fail(e)` ends a stream with the `E` it names, and that value rides Pekko's own
failure channel in a private wrapper with no stack trace that only `run`
unwraps — so fusion, backpressure and every other Pekko guarantee are the ones
Pekko gives. Every other throwable is something nobody declared and arrives as
`Exit.Died(cause)`. `run` never completes a failed stage: `Done`, `Failed(e)`
and `Died(cause)` are three cases of one value, and the `when` over them has no
`else`. There is no supervision strategy and no `resume`, so an element leaves
a pipeline through a named sink or not at all.

A `Died` is also reported at error through the actor system's own logger before
`run` hands it back, so a pipeline run for its effect with nobody reading its
stage still says what happened. The line names the operator, the element it was
processing and the line of the caller's own file that built the operator:

```
[ERROR] lark-stream: mapAsync died on Row(id=3, customer=null), built at IngestService.kt:41: java.lang.IllegalStateException: ledger down
```

The cause carries the same three facts — as a suppressed exception where a
caller's lambda threw or a stage failed, so that `Died` keeps the throwable
class the caller's own code threw and `is IllegalStateException` still matches,
and as its message where the library raised it, as a null completion is.

`Failing<E>` is an Arrow `Raise<E>`, so `raise(e)` is that same call under
lark's name for it and a `bind()` on a `Left` ends the stream with what the
`Left` holds. Everything a lark handler writes — `ensure`, `parZip`, a fork
awaited — an element body can write too, and the failure it names is the one
the stream already declares.

## Time a test owns

`tick`, `groupedWithin` and `restartOnDefect` wait on time. On `TestStreams`,
from `lark-stream-test`, they wait on a `TestClock` and on nothing else, so a
test moves time rather than sleeping through it:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

One stage runs at a time, in the same order every time. `start` returns once
the run is over or waiting on a later time, and each `adjust` stops at every
instant the run waits for on the way, so what falls due runs in time order
before `adjust` returns. A stage body that reads lark's `clock` reads the
test's. A body that blocks on anything else blocks the test with it.

## What is in the box

`lark-stream` puts the Kotlin standard library, `lark` and `arrow-core` on a
consumer's classpath, and nothing else: a description names no backend.

`lark-stream-pekko` adds `lark-pekko` — whose `await` is how `awaitExit`
waits, so an interrupt cancels the run rather than abandoning it — and
`pekko-stream` with the Scala runtime, Typesafe Config, the Reactive Streams
interfaces and the `ssl-config-core` it brings, and nothing else — no HTTP
library, no JSON library, no coroutines, no second functional stack.

Each module's `NoOtherDependenciesTest` asserts its list against the module's
real runtime classpath, so a dependency added to either is a build failure
rather than a judgement call.

## Working on it

```bash
./gradlew build
```

Tests, detekt, spotless, Kover's floor and the classpath test, in one command,
for the whole build. The module is what
[dipper's spec 0001](../specs/dipper/0001-a-stream-that-names-its-failure.md)
describes and nothing more; later specs say what is added, and `specs/` says
what is coming and in what order. Read [AGENTS.md](../AGENTS.md) before working
on it.

## License

Apache 2.0 — see [LICENSE](../LICENSE).

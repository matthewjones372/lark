# lark-stream

**A stream that names its failure.** A typed Kotlin view over Pekko Streams:
`Stream<E, A>` carries the failure type a pipeline can end with, the element
type can never be null, and running it answers an `Exit` that is `Done`,
`Failed(e)` or `Died(cause)` — never a dropped element and never a failed
future nobody read.

Every operator delegates to Pekko. `toSource()` and `Stream.from(source)` are
the way in and out, so nothing Pekko can do is out of reach.

The module is `lark-stream` and everything below is in
`io.github.matthewjones372.lark.stream`. It was its own library, dipper, until
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
    // Pekko Streams, lark and arrow-core arrive with it; nothing else does.
    implementation("io.github.matthewjones372:lark-stream:0.1.0-SNAPSHOT")
}
```

An untagged commit publishes `0.1.0-SNAPSHOT`, which is what
`./gradlew publishToMavenLocal` installs.

Rows in, receipts counted, declines diverted, and every import it takes:

<!-- readme-example -->
```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.divertLefts
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapAsync
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runFold
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

// Where a decline goes: a sink with a name, rather than a decider that drops it.
val declinedSink: Sink<Declined, CompletionStage<Done>> =
    Sink.foreach { declined -> println("declined ${declined.id}") }

val settled: CompletionStage<Exit<IngestError, Int>> =
    Stream.from(rows)                                                  // Stream<Nothing, Row>
        .mapOrFail { row ->                                            // Stream<IngestError, Customer>
            Customer(row.id, row.customer ?: fail(NoCustomer(row.id)))
        }
        .mapAsync(4) { customer -> ledger.settle(customer) }           // Stream<IngestError, Either<Declined, Receipt>>
        .divertLefts(to = declinedSink)                                // Stream<IngestError, Receipt>
        .runFold(0) { n, _ -> n + 1 }                                  // Run<IngestError, Int>
        .run(system)                                                   // CompletionStage<Exit<IngestError, Int>>
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

## The surface

| Operator | Answers |
|---|---|
| **Building** | |
| `Stream.from(elements: Iterable<A>): Stream<Nothing, A>` | a stream of what is already in hand |
| `Stream.from(source: Source<A, NotUsed>): Stream<Nothing, A>` | the way in from Pekko |
| `Stream.fail(error: E): Stream<E, Nothing>` | a stream that ends with the failure it names |
| `Stream.empty(): Stream<Nothing, Nothing>` | no elements and no failure |
| **Element by element** | |
| `Stream<E, A>.map(f: (A) -> B): Stream<E, B>` | `B` is bound to `Any`, so a nullable body does not compile |
| `Stream<E, A>.mapOrFail(f: Failing<E>.(A) -> B): Stream<E, B>` | as `map`, and the body may answer `fail(e)` |
| `Stream<E, A>.filter(predicate: (A) -> Boolean): Stream<E, A>` | the elements that match, the only place one is dropped on purpose |
| `Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B>` | up to `parallelism` stages at once, in the input's order; a `null` completion dies |
| **The split in `Either`** | |
| `Stream<E, A>.either(): Stream<Nothing, Either<E, A>>` | the failure as the last element, leaving none in the type |
| `Stream<E, Either<L, R>>.absolve(): Stream<E, R>` | back again: a `Left` fails the stream, for `L : E` |
| `Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R>` | every `Left` to that sink, every `Right` onwards — no predicate, no cast |
| **Leaving a failure type** | |
| `Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A>` | the declared failure handled, and a defect still dying |
| `Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A>` | `other` on a failure, not on an empty stream |
| **Running** | |
| `Stream<E, A>.runCollect(): Run<E, List<A>>` | a run described, collecting every element |
| `Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R>` | a run described, folding into `R` |
| `Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>>` | the one call that materialises, on the system it names |
| **The way out** | |
| `Stream<Nothing, A>.toSource(): Source<A, NotUsed>` | Pekko's own `Source`, once nothing is left to declare |

## How a failure travels

`fail(e)` ends a stream with the `E` it names, and that value rides Pekko's own
failure channel in a private wrapper with no stack trace that only `run`
unwraps — so fusion, backpressure and every other Pekko guarantee are the ones
Pekko gives. Every other throwable is something nobody declared and arrives as
`Exit.Died(cause)`. `run` never completes a failed stage: `Done`, `Failed(e)`
and `Died(cause)` are three cases of one value, and the `when` over them has no
`else`. There is no supervision strategy and no `resume`, so an element leaves
a pipeline through a named sink or not at all.

## What is in the box

`lark-stream` puts the Kotlin standard library, `lark`, `pekko-stream` with the
Scala runtime, Typesafe Config, the Reactive Streams interfaces and the
`ssl-config-core` it brings, and `arrow-core` on a consumer's classpath, and
nothing else — no HTTP library, no JSON library, no coroutines, no second
functional stack. `NoOtherDependenciesTest` asserts exactly that list against
the module's real runtime classpath, so a dependency added here is a build
failure rather than a judgement call.

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

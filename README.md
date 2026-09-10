# Lark

[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/lark?label=maven%20central)](https://central.sonatype.com/artifact/io.github.matthewjones372/lark)
[![Build](https://github.com/matthewjones372/lark/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/lark/actions/workflows/build.yml)
[![Coverage](https://img.shields.io/badge/coverage-%E2%89%A590%25%20enforced-brightgreen)](AGENTS.md#verifying)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/jdk-21%2B-brightgreen)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

`arrow-fx-coroutines` on virtual threads. Drop the `suspend`, swap the import,
and the body stays exactly as it was — the combinators take Arrow's own
`Raise`, so code already inside `either { }` needs no scope of lark's around it.

Each branch runs on a virtual thread of its own and is free to block, so a
service whose ports are JDBC or a client with no async surface gets the fork and
the join without a dispatcher to starve. It is `arrow-core` plus the JDK, and
nothing else — no coroutines, no second effect system.

## Use it

```kotlin
dependencies {
    // arrow-core comes with it; nothing else does
    implementation("io.github.matthewjones372:lark:0.1.0")
}
```

Every module is on Maven Central under the same group and version. Each one
brings itself and what its own name says, and `NoOtherDependenciesTest` in each
is where that claim is checked.

| Module | What it adds | Beyond `lark` |
|---|---|---|
| `lark` | `flock`, `parZip`, `parMap`, `raceN`, `resourceScope`, `Schedule`, `timeout`, `LarkLocal`, `Clock`, the log | `arrow-core` |
| `lark-pekko` | a Pekko dispatcher as the executor, and Pekko's stages awaited from a fork | `pekko-actor` |
| `lark-stream` | `Stream<E, A>` over Pekko Streams: the failure is in the type | `pekko-stream` |
| `lark-app` | an application as a value: the graph, probes, health, testing | nothing |
| `lark-app-pekko` | an actor as a node, keyed by the `ActorRef<T>` of its protocol | `pekko-actor-typed` |
| `lark-app-liquibase` | a changelog as a node, and reading the database depends on it | `liquibase-core` |
| `lark-app-typesafe` | a HOCON section as a node, and every fault at once | `com.typesafe:config` |
| `lark-otel` | a `Context` that crosses a fork, so a trace survives a `parMap` | `opentelemetry-api` |

An untagged commit builds `0.1.0-SNAPSHOT`, which is what
`./gradlew publishToMavenLocal` installs.

Before, on `arrow-fx-coroutines`:

```kotlin
suspend fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

After, on lark — `Err`, `Dashboard`, `users` and `orders` are the service's own,
and these are every import the function needs:

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.parZip

fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

`parZip` takes two branches through nine, as `arrow-fx-coroutines` does. Beside
it, one line each:

```kotlin
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.raceN

// every element on a fork of its own, answered in the iterable's order
fun totals(ids: List<Id>): Either<Err, List<Total>> = either { parMap(ids) { total(it).bind() } }

// the first branch to answer wins, on the side it was given
fun quote(id: Id): Either<Err, Either<Quote, Quote>> = either { raceN({ fast.quote(id) }, { slow.quote(id) }) }

// async and await, for a fork the combinators do not shape
fun user(id: Id): Either<Err, User> = either { flock { async { users.find(id).bind() }.await() } }
```

`flock { }` is the scope `async` forks in and the one thing
`arrow-fx-coroutines` has no name for here; a `Deferred` nobody awaits is still
joined when the scope closes, and its raise is still the block's `Left`. Outside
any `Raise`, `parZip`, `parMap` and `raceN` take plain `() -> A` branches and
answer with the combined value.

The first branch to raise or throw interrupts its siblings; `raceN` interrupts
the losers. Interrupt is the only cancellation the JDK has, so a cancelled
branch ends at its next interruptible blocking call, and a combinator returns
only once every fork it opened has ended.

Every forking combinator also takes the executor to fork on, ahead of its
branches — `parZip(on = pool, { … }, { … }) { … }`, `flock(on = pool) { }`,
`timeout(on = pool, 2.seconds) { }` — and a call that names none gets a new
virtual thread per fork, as above. A fork clears the interrupt flag as its body
leaves, so a cancelled branch never hands the flag to whatever the executor runs
next.

## Pekko

A Pekko application already has one place that names, sizes and instruments its
threads — the dispatcher config — and `lark-pekko` makes a dispatcher there the
executor lark forks on:

```kotlin
dependencies {
    // lark and pekko-actor come with it; nothing else does
    implementation("io.github.matthewjones372:lark-pekko:0.1.0")
}
```

The dispatcher is configured beside Pekko's own, and named by the part after
`pekko.actor.`:

```hocon
pekko.actor.lark {
  executor = "virtual-thread-executor"
}
```

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.parZip
import io.github.matthewjones372.lark.pekko.larkDispatcher
import org.apache.pekko.actor.ActorSystem

fun dashboard(system: ActorSystem, id: Id): Either<Err, Dashboard> = either {
    val lark = system.larkDispatcher()          // pekko.actor.lark; larkDispatcher("other") for another
    parZip(on = lark, { users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

`larkDispatcher` refuses a dispatcher whose executor is `fork-join-executor` or
`thread-pool-executor`, naming `pekko.actor.<id>.executor` in the message: a
lark fork blocks, and blocking the pool Pekko sizes for its actors is the
starvation Pekko's own documentation warns about. `virtual-thread-executor` is
accepted, and so is a `type = PinnedDispatcher`, whose thread is handed to
nothing else. Both the classic `ActorSystem` and the typed one are
`ClassicActorSystemProvider`, so either takes the extension.

A body forking there gets a `CompletionStage` back from everything Pekko has —
an HTTP request, an `ask`, a stream run into a sink — and `await()` is how a
fork waits for one:

```kotlin
import io.github.matthewjones372.lark.pekko.await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.Patterns
import java.time.Duration
import java.util.concurrent.CompletionStage

// `pricing.quote` is the application's own client, answering with a stage as Pekko's own APIs do
fun quote(system: ActorSystem, id: Id): Quote {
    val late: CompletionStage<Quote> = Patterns.after(Duration.ofMillis(50), system) { pricing.quote(id) }
    return late.await()
}
```

What parks is the fork that called it, and on a virtual thread that costs a
carrier nothing. A stage that failed rethrows the cause it was completed with
rather than the `CompletionException` or `ExecutionException` around it, so a
body catches what it declared. An interrupt — a closing scope, a `raceN` loser,
a `timeout` — cancels the stage with `cancel(true)` and rethrows the
`InterruptedException`, so a value that arrives afterwards is dropped rather
than answered to nobody. Pekko's Scala futures take `await()` too.

What `await()` does not change is what a stage holds: a stream run into
`Sink.seq` still buffers every element before completing, so awaiting one is
waiting for the whole collection, not reading a stream.

## Every error, not the first one

`parZipOrAccumulate` and `parMapOrAccumulate` run every branch to completion and
answer with all of the raises rather than the first: a form asking whether each
field is valid wants every complaint at once, not the earliest one. The scope
declares a `NonEmptyList` of the branches' error, and these are every import:

```kotlin
import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import io.github.matthewjones372.lark.parMapOrAccumulate
import io.github.matthewjones372.lark.parZipOrAccumulate

fun validate(form: Form): Either<NonEmptyList<Err>, Account> = either {
    parZipOrAccumulate({ name(form).bind() }, { email(form).bind() }) { n, e -> Account(n, e) }
}

fun accept(rows: List<Row>): Either<NonEmptyList<Err>, List<Entry>> = either {
    parMapOrAccumulate(rows) { entry(it).bind() }
}
```

Both take a `combine: (Err, Err) -> Err` as their first argument instead, for a
scope that declares one error and knows how to fold two into it. The errors
answer in branch order, and in the iterable's order, whichever branch raised
first. A throw is not accumulated: it ends the other branches as `parZip`'s
does, and the same instance is rethrown.

## Things that have to be given back

`resourceScope { }` runs a block with resources acquired in it, and releases
them in reverse acquisition order on the way out — by return, raise, throw or
interrupt. `install` names the pair, and the release is told which of those
happened:

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.ExitCase
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.resource
import io.github.matthewjones372.lark.resourceScope
import io.github.matthewjones372.lark.use

val connection: Resource<Connection> = resource {
    install({ pool.take() }) { held, exit ->
        if (exit is ExitCase.Failure) held.rollback() else held.commit()
        held.close()
    }
}

fun report(id: Id): Either<Err, Report> = either {
    resourceScope {
        val db = connection.bind()          // its release joins this scope's
        val file = install({ open(path) }) { it, _ -> it.close() }
        render(db.rows(id).bind(), file)    // a raise here releases both, in reverse
    }
}

fun rows(id: Id): List<Row> = connection use { it.rows(id) }
```

`ExitCase.Cancelled` carries the `InterruptedException`, because interrupt is
the only cancellation the JDK has. A raise gets `ExitCase.Completed`: it is not
an interrupt, and the `either` it leaves through answers its caller with a
value, so nothing about the scope failed. A release that throws when nothing
else had is the failure the caller sees, and one that throws after a failure is
suppressed onto it; either way the releases after it still run.

## Trying again, and giving up

A `Schedule<Input, Output>` is a value that answers each input with a decision:
carry on after a delay, or stop. `retry` feeds it what the action threw and
`repeat` feeds it what the action returned; the delays are waited out on the
calling virtual thread, so an interrupt ends the schedule where it is.

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.retry
import io.github.matthewjones372.lark.retryRaise
import io.github.matthewjones372.lark.timeout
import io.github.matthewjones372.lark.timeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val backoff = Schedule.exponential<Throwable>(100.milliseconds).jittered() and Schedule.recurs(5)

fun fetch(id: Id): Row = backoff.retry { client.row(id) }        // rethrows the last failure

// the same over a declared error: the last raise is the Left
fun row(id: Id): Either<Err, Row> = Schedule.recurs<Err>(5).retryRaise { rows.find(id).bind() }

fun quote(id: Id): Either<Err, Quote> = either {
    timeout(2.seconds) { slow.quote(id).bind() }              // throws TimeoutException
}

fun maybe(id: Id): Either<Err, Quote?> = either {
    timeoutOrNull(2.seconds) { slow.quote(id).bind() }        // null instead
}
```

`recurs`, `spaced`, `exponential`, `linear`, `fibonacci`, `forever`, `identity`,
`doWhile` and `doUntil` build one; `and`, `or`, `andThen`, `zipLeft`, `zipRight`,
`map`, `collect`, `delayed` and `jittered` combine them. `timeout` is a `raceN`
against a sleeper with the loser interrupted, so a block that does not answer in
time ends at its next interruptible call rather than being abandoned.

Virtual threads are why the floor is JDK 21. Before JDK 24 a blocking call
inside a `synchronized` block — which some JDBC drivers still make — pins its
carrier thread instead of parking it, so a service on 21 can still run out of
carriers; JEP 491 removes that pinning in 24.

## Status

`flock { }`, `async`/`await`, `parZip`, `parMap`, `raceN`,
`parZipOrAccumulate`/`parMapOrAccumulate`, `resourceScope`, `Schedule` and
`timeout` are here — all of
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md) and
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md) that
stayed, and all of
[`specs/0003-a-drop-in-for-arrow-fx.md`](specs/0003-a-drop-in-for-arrow-fx.md).

So are `LarkLocal`, the bound `Clock` and the log a fork carries
([0017](specs/0017-what-a-fork-inherits.md),
[0018](specs/0018-the-log-a-fork-carries.md)), and the application graph in
`lark-app` and `lark-app-pekko`
([0016](specs/0016-an-application-that-starts-as-a-value.md),
[0019](specs/0019-what-a-service-reads-from-outside-itself.md),
[0020](specs/0020-an-actor-is-a-node.md)).

[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- [Dipper](https://github.com/matthewjones372/dipper) — a stream that names its
  failure, over Pekko Streams. It is the `lark-stream` module here now, brought
  in with its history.

## What a fork inherits

A fork inherits nothing: `Thread.ofVirtual().start(command)` hands the body a
thread with no memory of the one that opened it. A `LarkLocal` is what crosses
it — read on the opening thread, rebound inside the task, so a value bound
outside a `parMap` is readable in every branch, on a Pekko dispatcher as much as
on a virtual thread of lark's own.

```kotlin
import io.github.matthewjones372.lark.larkLocal
import io.github.matthewjones372.lark.parMap

val requestId = larkLocal { "none" }

requestId.locally("abc-123") { parMap(rows) { row -> requestId.get() } }   // every branch reads it
```

The clock is lark's own first user of it. `Schedule` waits through whichever
`Clock` the thread inherited, so a test of a backoff does not wait one:

```kotlin
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.retry
import kotlin.time.Duration.Companion.seconds

val backoff = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(5)

clock.locally(fixedClock()) { backoff.retry { flaky.row(id) } }            // microseconds, not minutes
```

`TestClock` is the other one: a broken wall clock that moves when a test moves
it. `adjustWhenBlocked` waits until every sleep is on a time still ahead before
moving, because nothing here knows a fork has reached its `sleep` the way a
fiber runtime does.

The log rides the same binding, which is why a correlation id survives a fork
where an MDC does not:

```kotlin
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan

logAnnotated("correlation_id" to request.id) {
    logSpan("register") { parMap(request.items) { item -> logInfo("checking $item") } }
}
```

`capturingLogs { }` binds a logger a test can read, so a claim about logging is
a claim about values.

## Applications

`lark-app` is a dependency graph as a value. A recipe names what it builds and
takes what it needs as parameters, so the graph is data before anything runs:
`validate` says what is missing before a recipe is called, `subgraph` gives a
test four nodes instead of forty, `overriding` refuses a fake under a key
nothing asked for, and starting one runs a topological layer at a time on lark's
forks with releases in reverse topological order.

```kotlin
import io.github.matthewjones372.lark.app.runApp
import io.github.matthewjones372.lark.app.single

val app = config + persistence + domain + web

fun main() {
    exitProcess(runApp(app) { server: HttpServer -> server.start(); awaitShutdown() }.code)
}
```

A probe is what makes "started" mean "ready" rather than "constructed", and the
same probes answer `/ready` afterwards through a `HealthRegistry` a route takes
as a dependency. `lark-app-pekko` makes an actor a node, keyed by the
`ActorRef<T>` of its protocol. There are no annotations, no processor and no
effect type. [`docs/app.md`](docs/app.md) is the whole of it.

## Streams

`lark-stream` is `Stream<E, A>` over Pekko Streams: the failure a pipeline
can end with is in the type, an element can never be null, and running one
answers an `Exit` that is `Done`, `Failed(e)` or `Died(cause)` rather than a
stage nobody read. A `Died` is reported at error through the actor system's own
logger too, naming the operator, the element and the caller's line that built
it, so a pipeline run for its effect still says what happened. Every operator
delegates to Pekko, and `toSource()` and `Stream.from(source)` are the way out
and in, so nothing Pekko can do is out of reach.

It was a library of its own, dipper, until this repository took it in; the
code is the same under `io.github.matthewjones372.lark.stream`, and it depends
on `lark` and `lark-pekko`, so a handler and the stream it runs share one
vocabulary: an element body is a `Raise`, `mapPar` gives it a virtual thread,
and `awaitExit` folds the run's `Exit` back into the handler that started it.

```kotlin
dependencies {
    // Pekko Streams, lark, lark-pekko and arrow-core come with it; nothing else does
    implementation("io.github.matthewjones372:lark-stream:0.1.0")
}
```

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

### Before and after

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

### Missing is a failure, not an empty stream

A stage that completed with `null`, an empty `Optional`, a lookup that found
nothing: every builder that reads absence as emptiness makes a source with no
elements out of a miss, and the run answers `Done` having processed nothing.
`single` and `of` refuse the nullable where the value is, `fromStage` checks
the completion the type argument lied about, and `orFailIfEmpty` is the word a
caller says over a source lark did not build:

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

The document carries the rest, with the detekt snippet for the builders that
read absence as emptiness — the code a library cannot reach is the code that
never enters it.

[`docs/stream.md`](docs/stream.md) is the operator table — every builder,
combinator and way out with what it answers — and how a declared failure
travels.

## Licence

Apache 2.0.

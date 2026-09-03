# Lark

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
    implementation("io.github.matthewjones372:lark:0.1.0-SNAPSHOT")
}
```

An untagged commit publishes `0.1.0-SNAPSHOT`, which is what
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
    implementation("io.github.matthewjones372:lark-pekko:0.1.0-SNAPSHOT")
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
[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- [Dipper](https://github.com/matthewjones372/dipper) — a stream that names its
  failure, over Pekko Streams. It is the `lark-stream` module here now, brought
  in with its history.

## Streams

`lark-stream` is `Stream<E, A>` over Pekko Streams: the failure a pipeline
can end with is in the type, an element can never be null, and running one
answers an `Exit` that is `Done`, `Failed(e)` or `Died(cause)` rather than a
stage nobody read. Every operator delegates to Pekko, and `toSource()` and
`Stream.from(source)` are the way out and in, so nothing Pekko can do is out
of reach.

It was a library of its own, dipper, until this repository took it in; the
code is the same under `io.github.matthewjones372.lark.stream`, and it depends
on `lark` and `lark-pekko`, so a handler and the stream it runs share one
vocabulary: an element body is a `Raise`, `mapPar` gives it a virtual thread,
and `awaitExit` folds the run's `Exit` back into the handler that started it.

```kotlin
dependencies {
    // Pekko Streams, lark, lark-pekko and arrow-core come with it; nothing else does
    implementation("io.github.matthewjones372:lark-stream:0.1.0-SNAPSHOT")
}
```

Rows in, receipts to one sink, declines to another, and every import it takes:

<!-- readme-example -->
```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.pekko.await
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.awaitExit
import io.github.matthewjones372.lark.stream.divertLefts
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.mapPar
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
            .mapPar(4) { customer -> ledger.settle(customer).await() }  // one virtual thread per element
            .divertLefts(to = declinedSink)                            // Stream<IngestError, Receipt>
            .runWith(receiptSink)                                      // Run<IngestError, Done>
            .run(system),                                              // CompletionStage<Exit<IngestError, Done>>
    )                                                                  // Done → the value, Failed → raise, Died → throw
}
```

[`docs/stream.md`](docs/stream.md) is the operator table — every builder,
combinator and way out with what it answers — and how a declared failure
travels.

## Licence

Apache 2.0.

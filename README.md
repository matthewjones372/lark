# Lark

[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/lark?label=maven%20central)](https://central.sonatype.com/artifact/io.github.matthewjones372/lark)
[![Build](https://github.com/matthewjones372/lark/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/lark/actions/workflows/build.yml)
[![Coverage](https://img.shields.io/badge/coverage-%E2%89%A590%25%20enforced-brightgreen)](AGENTS.md#verifying)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/jdk-21%2B-brightgreen)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-experimental-orange)](#what-this-is)

`arrow-fx-coroutines` on virtual threads. Drop the `suspend`, swap the import,
and the body stays exactly as it was — the combinators take Arrow's own
`Raise`, so code already inside `either { }` needs no scope of lark's around it.

Each branch runs on a virtual thread of its own and is free to block, so a
service whose ports are JDBC or a client with no async surface gets the fork and
the join without a dispatcher to starve. It is `arrow-core` plus the JDK, and
nothing else — no coroutines, no second effect system.

## What this is

A scratchpad. Lark is where ideas about what a Kotlin service looks like
without coroutines get tried: a fork that inherits, an application that is a
value, a stream that names its failure. It is on Maven Central so that trying
one takes a dependency line rather than a checkout — not because any of it is
finished.

Nothing here has run in production. The API moves between versions and will
keep moving; `lark-app`'s runner changed shape twice in the week it was written.
Several modules exist because a question came up, and have no consumer beyond
their own tests.

Take it for an experiment, a spike, or a read. Pin the version if you do, and
expect the next one to break you.

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
| `lark-stream` | `Stream<E, A>`, described: the failure is in the type, and no backend is named | nothing |
| `lark-stream-pekko` | runs a `Stream` on Pekko Streams, and the operators that take Pekko's types | `pekko-stream` |
| `lark-stream-forks` | runs a `Stream` as a pull loop on one virtual thread, for the operators that need no second one | nothing |
| `lark-stream-actors` | runs a `Stream` on `lark-actor`: a run is an actor pulling a batch a step, and `mapPar`, `buffer` and the fan-ins are its child actors. Against Forks: `mapPar` 5.7× faster, a run started 2.1× faster, `buffer` and `merge` 1.5–2.4× faster; the plain chain a quarter slower ([numbers](lark-stream-benchmarks/README.md)) | nothing |
| `lark-stream-test` | runs a `Stream` on a `TestClock` the test moves: an hour of `tick` is one `adjust`, and each `adjust` returns with what fell due | nothing |
| `lark-kafka` | a Kafka topic as a `Stream` on any backend: each offset committed once its record's work is done, and a record that fails to decode as a value ([`docs/kafka.md`](docs/kafka.md)) | `kafka-clients` |
| `lark-kafka-pekko` | the same through Pekko's own Kafka connector: prefetch, batched commits and a draining stop | `pekko-connectors-kafka` |
| `lark-actor` | an actor as a `Behaviour`: a state and a step, spawned in a `flock` on virtual threads or run synchronously by `.test()` (spec [0059](specs/0059-an-actor-without-an-actor-system.md)); the [actors guide](docs/actors.md) shows it in use. Against Pekko: faster `tell` with half the allocation, 6× faster ping-pong, 5.7× faster on blocking steps, 23% faster waking 10,000 idle actors at once, and 56% of the heap per idle actor ([numbers](lark-actor-benchmarks/README.md)); and `persistent(batch = n)`, which writes the commands already waiting in one append: twenty times the payments a second into one hot account on Postgres (spec [0086](specs/0086-a-journal-written-a-batch-at-a-time.md)) | nothing |
| `lark-app` | an application as a value: the graph, its faults, probes, health, testing | nothing |
| `lark-app-pekko` | an actor as a node, keyed by the `ActorRef<T>` of its protocol | `pekko-actor-typed` |
| `lark-actor-remote` | `lark-actor` across nodes: codecs you own, one TCP connection per peer on JDK sockets and virtual threads, tell and ask across nodes, watch across nodes, and dead letters for what cannot be delivered (spec [0068](specs/0068-an-actor-on-another-node.md)); with `tls`, mutual TLS 1.3 where each node is the name its certificate gives (spec [0073](specs/0073-nodes-that-know-each-other.md)). Against Pekko's Artery over TCP: a remote ask 2.8× faster and a burst of tells 1.9× faster ([numbers](lark-actor-benchmarks/README.md)); the [cluster guide](docs/cluster.md) shows it in use | nothing |
| `lark-actor-remote-protobuf` | `lark-actor-remote`'s messages in Protobuf: a codec for a generated message, `oneOf` for a protocol of several under fixed tags, and `asked` for an ask whose request is one (spec [0071](specs/0071-messages-in-protobuf-or-avro.md)) | `com.google.protobuf:protobuf-java` |
| `lark-actor-remote-avro` | `lark-actor-remote`'s messages in Avro: a codec for a specific record in single-object encoding, read across its versions through a `SchemaStore`, and `asked` for an ask whose request is one (spec [0071](specs/0071-messages-in-protobuf-or-avro.md)) | `org.apache.avro:avro` |
| `lark-actor-remote-kotlinx` | `lark-actor-remote`'s messages, and the journal's events and snapshots, from a service's own `@Serializable` data classes: `codec`, `oneOf` for several under fixed tags written as a protobuf `oneof`, `asked`, and `proto()` for the `.proto` the classes make (spec [0093](specs/0093-messages-from-kotlin-data-classes.md)); kotlinx's protobuf support is experimental | `org.jetbrains.kotlinx:kotlinx-serialization-protobuf` |
| `lark-actor-journal-jdbc` | `lark-actor`'s journal on JDBC: `JdbcJournal(dataSource)` over one table every node reaches, its tables shipped as a Liquibase changelog for Postgres, and two writers for one id settled by the primary key (spec [0072](specs/0072-events-that-outlive-the-node.md)); `JdbcSnapshots`, the newest snapshot per id (spec [0074](specs/0074-a-recovery-that-does-not-replay-everything.md)); and a `JournalFeed` with `JdbcOffsets` for read models (spec [0075](specs/0075-a-read-model-that-follows-the-journal.md)); `ShardedJournal` and `ShardedSnapshots` split it across databases by id (spec [0088](specs/0088-a-journal-across-databases.md)); the [cluster guide](docs/cluster.md) shows it in use | — |
| `lark-actor-projection` | `lark-actor`'s journal as a lark-stream `Stream`: `Projection.follow` reads every event of one kind from a `JournalFeed`, polling once caught up, and `runProjecting()` stores each event's offset once its work is done, so a read model resumes where it left off (spec [0075](specs/0075-a-read-model-that-follows-the-journal.md)); with `Prune.after`, snapshots delete only what named read models have read (spec [0077](specs/0077-pruning-that-waits-for-read-models.md)) | — |
| `lark-cluster` | `lark-actor-remote` as a cluster with no coordinator: seeds from a list, DNS or SRV, membership agreed by SWIM gossip over the same transport, member events, and a partition resolved by majority, quorum or lease with the losing side downing itself first (spec [0069](specs/0069-nodes-that-agree-who-is-up.md)), and entities and singletons placed on whichever member owns them, moved between members without ever running twice (spec [0070](specs/0070-an-entity-on-whichever-node-owns-it.md)). Against Pekko Cluster, three nodes a side: sharded tell and ask, a persistent append, reliable and durable sends, and a topic's fan-out, with the result pending a run on the machine the other numbers were taken on ([numbers](lark-actor-benchmarks/README.md)); the [cluster guide](docs/cluster.md) shows it in use | nothing |
| `lark-cluster-kubernetes` | `lark-cluster` on Kubernetes: seeds from the pods API, and a `Lease` object to break an even split | `io.fabric8:kubernetes-client` |
| `lark-cluster-aws` | `lark-cluster` on AWS: seeds from Cloud Map or ECS, and a DynamoDB item as the lease that breaks an even split | `software.amazon.awssdk` (`servicediscovery`, `ecs`, `dynamodb`) |
| `lark-app-actor` | a `lark-actor` flock as a node for the application's life, and an actor as a node keyed by the `ActorRef<M>` of its protocol (spec [0062](specs/0062-actors-that-find-each-other.md)) | nothing |
| `lark-app-cluster` | a `lark-cluster` membership as a node, joined as a HOCON section says: the backend found by name on the classpath, its client closed after the node leaves, and a node the others downed ending its process (spec [0096](specs/0096-a-cluster-joined-from-config.md)) | `lark-app-typesafe` |
| `lark-app-liquibase` | a changelog as a node, and reading the database depends on it | `liquibase-core` |
| `lark-app-typesafe` | a HOCON section as a node, every fault at once, and a setting that picks a module | `com.typesafe:config` |
| `lark-otel` | a `Context` that crosses a fork, so a trace survives a `parMap` | `opentelemetry-api` |
| `lark-slf4j` | lark's own lines through the backend a service already configured, annotations in the MDC | `slf4j-api` |
| `lark-micrometer` | counters, gauges and histograms into the `MeterRegistry` a service already has | `micrometer-core` |
| `lark-app-gradle` | a Gradle plugin: every graph in a project checked and drawn as it compiles | `gradleApi()` |

A commit after the latest tag builds the next patch version as a
`-SNAPSHOT` (after `v0.5.0`, `0.5.1-SNAPSHOT`), which is what
`./gradlew publishToMavenLocal` installs.

One module is built and tested but not published. `lark-structured` has
`parZip`, `parMap`, `raceN` and `timeout`, with the same names and shapes as
lark's, running each call's branches in the JDK's `StructuredTaskScope`. They
show under their caller in a thread dump and inherit `ScopedValue` bindings. That API is a preview in
JDK 27, and the module is published when JDK 28 makes it final
([0040](specs/0040-a-flock-the-jdk-can-see.md)).

`lark-bank` is not published either: it is an application to run.
`./gradlew :lark-bank:run` starts three nodes in one JVM with accounts and
transfers sharded across them. It serves a page to send money on at
<http://localhost:8081>, and an admin page at `/admin` that streams the
cluster's numbers, with a load button and "crash n3"
([0094](specs/0094-a-bank-you-can-watch.md)).

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

// and cancel, for a fork that stopped being worth waiting for
fun dashboard(id: Id): Either<Err, Dashboard> = either {
    flock {
        val report = async { reports.build(id) }
        if (budget.check(id).bind().hasCredit) render(report.await()) else { report.cancel(); Empty }
    }
}
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

A fork can also wait to be asked. `async(start = Lazy) { }` does not reach the
executor until something awaits it, so work a later branch turns out not to want
is never done — and a lazy fork nobody awaits never runs at all, which means its
raise is never the block's `Left` where an eager one's would be. The flag is on
the call rather than the scope on purpose: a scope where nothing started until
it was awaited would turn a fan-out into sequential code, which is the one thing
`flock` is for.

`cancel()` is the same stop, aimed by hand at one fork rather than by a
combinator at a branch: it interrupts and returns once that fork has ended, so
nothing it owns is still running afterwards. A cancelled fork's outcome counts
as noticed, so the scope does not answer with it — including a raise it got to
before the interrupt landed. A body that swallowed the interrupt and returned
anyway still has its value, and `await()` after a `cancel()` gives it back:
`cancel()` is a request, not a verdict.

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

The floor is JDK 25. Before JDK 24 a blocking call inside a `synchronized`
block pins its carrier thread instead of parking it, so a service on 21 can run
out of carriers, and did: lark-bank's load generator deadlocked on 21 with every
carrier pinned inside Jackson's serializer cache. JEP 491 removes that pinning
in 24, and 25 is the LTS that has it. The Gradle plugin and the compiler plugin
still compile for 21, as they run inside whatever JDK the build tools are on.

## Status

Experimental, in the sense [above](#what-this-is): everything below works and is
tested, and none of it is settled.

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

Where those lines end up is a dependency rather than a call. Nothing is bound,
so they go to stderr — enough to watch a service start and no more. Put
`lark-slf4j` on the classpath and they go wherever the rest of the service
already logs:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-slf4j:0.2.1")
}
```

It registers itself through a `ServiceLoader`, so there is no line in `main` to
remember. The annotations arrive as MDC entries rather than as text on the end
of the message, which is what carries the claim above through to the backend: a
correlation id survives the fork *and* reaches `%X{correlation_id}`, a JSON
encoder and a field search. Appending it to the message would survive the fork
and be unreadable to every one of them.

`logger.locally(MyLogger()) { … }` still wins, for a test, a backend of your
own, or one block that should go somewhere else.

A number is the same bargain. Nothing to declare, nothing to thread through the
graph, and no node taking a registry:

```kotlin
counter("petshop.adoptions").increment()
gauge("petshop.queue.depth").set(waiting.toDouble())
timed("petshop.adopt") { shop.adopt(id, by) }

metricTagged("species" to "tortoise") { counter("petshop.adoptions").increment() }
```

`metricTagged` rides the same binding as the log's, so a tag is on every
measurement a fork takes underneath it. It is deliberately not the annotations
`logAnnotated` binds: those are written to be unique, and a tag whose values are
unbounded is one time series per request. Put `lark-micrometer` on the classpath
and the numbers reach whatever registry the service already has; bind nothing
and they are recorded nowhere, which costs nothing and throws nothing.
`capturingMetrics { }` is the test's way in.

## Applications

`lark-app` is a dependency graph as a value. A recipe names what it builds and
takes what it needs as parameters, so the graph is data before anything runs:
`findings` says what is wrong before a recipe is called, `subgraph` gives a
test four nodes instead of forty, `overriding` refuses a fake under a key
nothing asked for, and starting one runs a topological layer at a time on lark's
forks with releases in reverse topological order.

```kotlin
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.runApp
import kotlin.reflect.typeOf

object App : LarkApp<HttpServer>() {
    override val module: Module = config + persistence + domain + web
    override fun AppScope.run(root: HttpServer) { root.start(); awaitShutdown() }
}

fun main(): Unit = exitProcess(runApp(App).code)
```

The graph is a value, so it can also be drawn — `render()` answers mermaid, and
`larkWiring` writes one per application:

![A wiring graph: Tuning above Database and Memo, Database above Accounts and
Postings, and Opening, Reporting and Memo all above Frontage.](docs/wiring.png)

`Database` and `Memo` have no edge between them, so they start at the same time;
every path into `Frontage` is something that must be ready before the door
opens. Neither is visible in the code that built it.

A graph that declares the node it starts from is one a build can read without
running `main`, which is what `lark-app-gradle` does as the project compiles:

```
lark-app wiring

❯ error: missing DataSource
❯     for OrderRepo          Wiring.kt:42

❯ warning: nothing reaches KafkaProducer    Kafka.kt:9
```

A node remembers where it was written, so a fault names the recipe to edit. A
missing key and a cycle are errors; a key provided twice and a node no root
reaches are warnings. Applying the plugin is the whole of the per-project cost,
and it draws each graph into `build/reports/lark` beside the report.

A configuration answers the same question. A hierarchy of HOCON files resolves
to one document, and `lark-app-typesafe` says which file won and what it beat:

```kotlin
import io.github.matthewjones372.lark.app.typesafe.layeredConfig
import io.github.matthewjones372.lark.app.typesafe.origins

layeredConfig().origins().report()
```

```
lark-app configuration

petshop.arrivalsEvery  30s           reference.conf:4
petshop.db.password    ●●●●●●        application.conf:5
petshop.db.pool        16            application.conf:4
                       overrides 4 at reference.conf:3
petshop.port           9090          application.conf:2
                       overrides 8080 at reference.conf:2
```

Values are redacted by default — a name match is the floor, `Secrets.and(path)`
is the exact answer, and `secret(path)` reads one as a value that prints as the
mask through `toString`, so a log line cannot leak it either.

A probe is what makes "started" mean "ready" rather than "constructed", and the
same probes answer `/ready` afterwards through a `HealthRegistry` a route takes
as a dependency. `lark-app-pekko` makes an actor a node, keyed by the
`ActorRef<T>` of its protocol.

There are no annotations, no processor and no effect type. A module is an
expression, so there is nothing for KSP to read: KSP models declarations, and
`val app = single { } + single { }` is neither. The check runs the expression
instead, which is why it sees an actor node and a module assembled in a
conditional.

Running the expression means waiting for a build, so `lark-app-compiler` reads
the same graph out of the compiler's own syntax tree and says the same sentence
where you are typing it:

![IntelliJ underlining `singleOf(::ActorPetShop)` in red, with the tooltip
"lark-app: PetShop needs ActorRef<Shop>, and nothing builds it".](docs/editor-error.png)

It is a K2 checker with no backend half, which is what lets the IDE run it. It
follows what it knows — the factories above, `+`, `boundTo`, names in the same
compilation unit, the branches of a `when` — and abandons the whole application
the moment it meets a shape it does not, because a red line under working code
costs more than a fault found a moment later. `larkWiring` stays the gate: it
runs the graph, so it sees what no reader of source can.

Two things to know before turning it on. IntelliJ runs no third-party compiler
plugin in the editor until
`kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in the registry;
and a module that arrives from another Gradle module has no source here to read,
so a graph assembled across modules is one the editor stays quiet about.
[`docs/app.md`](docs/app.md) is the whole of it.

## Streams

`lark-stream` is `Stream<E, A>`: the failure a pipeline can end with is in the
type, an element can never be null, and running one answers an `Exit` that is
`Done`, `Failed(e)` or `Died(cause)` rather than a stage nobody read. A `Died`
is logged at error too, naming the operator, the element and the caller's line
that built it, so a pipeline run for its effect still says what happened.

A stream is a description, and it names no backend. The backend is a value
handed over where the run starts, and each lives in a module of its own, which
brings `lark-stream` with it:

```kotlin
dependencies {
    // Pekko Streams: lark-stream, Pekko, lark-pekko and arrow-core come with it
    implementation("io.github.matthewjones372:lark-stream-pekko:0.5.0")
    // or a pull loop on one virtual thread, with nothing under it but lark-stream
    // implementation("io.github.matthewjones372:lark-stream-forks:0.5.0")
    // or runs as actors of a lark-actor flock: Actors(flock)
    // implementation("io.github.matthewjones372:lark-stream-actors:0.5.0")

    // time a test owns: tick, groupedWithin and restartOnDefect on a TestClock
    testImplementation("io.github.matthewjones372:lark-stream-test:0.5.0")
}
```

One description, and the backend picked where it runs:

<!-- backend-example -->
```kotlin
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.TestStreams
import io.github.matthewjones372.lark.stream.filter
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runFold
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletionStage

data class Order(val id: Int, val pence: Long?)

data class Unpriced(val id: Int)

val orders = listOf(Order(1, 1200), Order(2, 800), Order(3, 4500))

// What happens to an order, and the failure it can end with. No backend is named here.
val takings: Run<Unpriced, Long> =
    Stream.from(orders)
        .mapOrFail { order -> order.pence ?: raise(Unpriced(order.id)) }
        .filter { pence -> pence >= 1000 }
        .runFold(0L) { total, pence -> total + pence }

val system: ActorSystem = ActorSystem.create("shop")

val onPekko: CompletionStage<Exit<Unpriced, Long>> = takings.run(PekkoStreams(system))
val onForks: CompletionStage<Exit<Unpriced, Long>> = takings.run(Forks())
val inATest: CompletionStage<Exit<Unpriced, Long>> = takings.run(TestStreams(TestClock()))
```

Each answers the same `Exit`. A backend refuses a run it cannot finish before
anything starts, naming the operator and the line that built it: `Forks` runs
nothing that needs a second thread or a clock, and no backend runs another's
`Source`.

On `TestStreams`, `tick`, `groupedWithin`, `restartOnDefect`'s delays and
`mapPar` wait on the test's `TestClock` and on nothing else, and each `adjust`
returns once everything that fell due by then has run, in time order. An hour
of one-a-minute ticks is one call:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

### What runs is not what you wrote, and you can see both

Before a run starts, adjacent element-at-a-time stages (`map`, `filter`,
`mapOrFail` and the like) are fused into one, a `take` of a `take` is one
`take`, and a `catchAll` over a stream that cannot fail is dropped. A defect
still names the operator and the line it came from. A five-stage chain on Pekko
went from 150 ns to 91 ns an element.

`render()` draws the description as text or Mermaid, each stage with the line
that built it, and `render(optimised = true)` draws what actually runs:

```
Stream.from
fused[map, map, filter, map, mapOrFail]  Pipelines.kt:15
runFold                                  Pipelines.kt:20
```

`measured(Measured("ingest", metrics))` reports each stage's elements, the time
in its body and the time it waited for demand, through lark's `Metrics`. A
`Profiler` keeps those numbers, and `render(profile = profiler.profile())`
draws where the time went:

```
map          Pipelines.kt:15  3% · 1.0 µs busy · 3.0 µs waiting · 100000 out
map          Pipelines.kt:16  67% · 20.0 µs busy · 3.0 µs waiting · 100000 out
filter       Pipelines.kt:17  3% · 1.0 µs busy · 3.0 µs waiting · 50000 out
```

### On Pekko Streams

`lark-stream-pekko` also has the operators that take Pekko's own types:
`Stream.from(source)` and `toSource()` in and out, `divertLefts` and `runWith`
to a `Sink`, and `run(system)`, which is `run(PekkoStreams(system))`. A handler
and the stream it runs share one vocabulary: an element body is a `Raise`,
`mapPar` gives it a virtual thread, and `awaitExit` folds the run's `Exit` back
into the handler that started it.

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

[`docs/stream.md`](docs/stream.md) has the rest: the same ingest written
against raw Pekko and against lark-stream side by side, why a missing value is
a failure rather than an empty stream, the operator table, and how a declared
failure travels. `lark-stream` was a library of its own, dipper, until this
repository took it in.

## Licence

Apache 2.0.

# Lark

[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/lark?label=maven%20central)](https://central.sonatype.com/artifact/io.github.matthewjones372/lark)
[![Build](https://github.com/matthewjones372/lark/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/lark/actions/workflows/build.yml)
[![Coverage](https://img.shields.io/badge/coverage-%E2%89%A590%25%20enforced-brightgreen)](AGENTS.md#verifying)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/jdk-25%2B-brightgreen)](https://openjdk.org/projects/jdk/25/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-experimental-orange)](#what-this-is)

Lark is a version of `arrow-fx-coroutines` that runs on virtual threads instead
of coroutines. To move a function over, remove the `suspend` and change the
import; the body stays the same. The combinators take Arrow's own `Raise`, so
code already inside `either { }` does not need any extra scope from lark.

Each branch runs on its own virtual thread and is allowed to block. A service
that talks to JDBC, or to a client with no async API, can fork and join without
the risk of starving a dispatcher. The only dependencies are `arrow-core` and
the JDK: there are no coroutines and no second effect system.

## What this is

A scratchpad. Lark is a place to try out ideas about what a Kotlin service looks
like without coroutines, such as a fork that inherits context, an application
defined as a value, and a stream whose failure type is part of its type. It is
on Maven Central so that trying one of these takes a dependency line rather
than a checkout. Being published does not mean any of it is finished.

Nothing here has run in production. The API changes between versions and will
keep changing; `lark-app`'s runner changed shape twice in the week it was
written. Several modules exist because a question came up, and have no user
beyond their own tests.

Treat it as an experiment, a spike, or something to read. If you use it, pin
the version and expect the next one to break things.

## Use it

```kotlin
dependencies {
    // arrow-core comes with it; nothing else does
    implementation("io.github.matthewjones372:lark:0.9.0")
}
```

Every module is on Maven Central under the same group and version. Each one
brings only itself and the dependency its name says, and the
`NoOtherDependenciesTest` in each module checks that.

| Module | What it adds | Beyond `lark` |
|---|---|---|
| `lark` | `flock`, `parZip`, `parMap`, `raceN`, `resourceScope`, `Schedule`, `timeout`, `policy`, `LarkLocal`, `Clock`, the log | `arrow-core` |
| `lark-pekko` | a Pekko dispatcher as the executor, and Pekko's stages awaited from a fork | `pekko-actor` |
| `lark-stream` | `Stream<E, A>` as a description: the failure is in the type, and no backend is named | nothing |
| `lark-stream-pekko` | runs a `Stream` on Pekko Streams, plus the operators that take Pekko's types | `pekko-stream` |
| `lark-stream-forks` | runs a `Stream` as a pull loop on one virtual thread, for the operators that don't need a second one | nothing |
| `lark-stream-actors` | runs a `Stream` on `lark-actor`: a run is an actor pulling one batch per step, and `mapPar`, `buffer` and the fan-ins are its child actors. Compared with Forks: `mapPar` 5.7× faster, starting a run 2.1× faster, `buffer` and `merge` 1.5 to 2.4× faster; the plain chain is about a quarter slower ([numbers](lark-stream-benchmarks/README.md)) | nothing |
| `lark-stream-test` | runs a `Stream` on a `TestClock` that the test moves: an hour of `tick` is one `adjust`, and each `adjust` returns with whatever fell due | nothing |
| `lark-test` | `story { Given / When / Then }`: steps that return values, and a failure message that shows the story up to the step that broke, in colour under `FORCE_COLOR` or IntelliJ (spec [0116](specs/0116-a-story-in-colour.md)); and `eventually`, which retries until the block stops throwing and gives up on time by the inherited clock, so a test of a whole service can wait inside `use` without `suspend` (spec [0115](specs/0115-a-test-that-reads-as-a-story.md)) | nothing |
| `lark-kafka` | a Kafka topic as a `Stream` on any backend: each offset is committed once its record's work is done, and a record that fails to decode arrives as a value ([`docs/kafka.md`](docs/kafka.md)) | `kafka-clients` |
| `lark-kafka-pekko` | the same through Pekko's own Kafka connector: prefetch, batched commits and a draining stop | `pekko-connectors-kafka` |
| `lark-actor` | an actor as a `Behaviour`: a state and a step, spawned in a `flock` on virtual threads or run synchronously by `.test()` (spec [0059](specs/0059-an-actor-without-an-actor-system.md)); the [actors guide](docs/actors.md) shows it in use. Compared with Pekko: faster `tell` with half the allocation, 6× faster ping-pong, 5.7× faster on blocking steps, 23% faster waking 10,000 idle actors at once, and 56% of the heap per idle actor ([numbers](lark-actor-benchmarks/README.md)). Also `persistent(batch = n)`, which writes the commands already waiting in one append: twenty times the payments per second into one hot account on Postgres (spec [0086](specs/0086-a-journal-written-a-batch-at-a-time.md)) | nothing |
| `lark-app` | an application as a value: the graph, its faults, probes, health, testing | nothing |
| `lark-app-pekko` | an actor as a node, keyed by the `ActorRef<T>` of its protocol | `pekko-actor-typed` |
| `lark-actor-remote` | `lark-actor` across nodes: codecs you own, one TCP connection per peer on JDK sockets and virtual threads, tell, ask and watch across nodes, and dead letters for what cannot be delivered (spec [0068](specs/0068-an-actor-on-another-node.md)); with `tls`, mutual TLS 1.3 where each node is identified by the name on its certificate (spec [0073](specs/0073-nodes-that-know-each-other.md)). Compared with Pekko's Artery over TCP: a remote ask 2.8× faster and a burst of tells 1.9× faster ([numbers](lark-actor-benchmarks/README.md)); the [cluster guide](docs/cluster.md) shows it in use | nothing |
| `lark-actor-remote-protobuf` | `lark-actor-remote`'s messages in Protobuf: a codec for a generated message, `oneOf` for a protocol of several messages under fixed tags, and `asked` for an ask whose request is one (spec [0071](specs/0071-messages-in-protobuf-or-avro.md)) | `com.google.protobuf:protobuf-java` |
| `lark-actor-remote-avro` | `lark-actor-remote`'s messages in Avro: a codec for a specific record in single-object encoding, read across its versions through a `SchemaStore`, and `asked` for an ask whose request is one (spec [0071](specs/0071-messages-in-protobuf-or-avro.md)) | `org.apache.avro:avro` |
| `lark-actor-remote-kotlinx` | `lark-actor-remote`'s messages, and the journal's events and snapshots, from a service's own `@Serializable` data classes: `codec`, `oneOf` for several under fixed tags written as a protobuf `oneof`, `asked`, and `proto()` for the `.proto` the classes produce (spec [0093](specs/0093-messages-from-kotlin-data-classes.md)); kotlinx's protobuf support is experimental | `org.jetbrains.kotlinx:kotlinx-serialization-protobuf` |
| `lark-actor-journal-jdbc` | `lark-actor`'s journal on JDBC: `JdbcJournal(dataSource)` over one table every node can reach, with its tables shipped as a Liquibase changelog for Postgres, and two writers for one id settled by the primary key (spec [0072](specs/0072-events-that-outlive-the-node.md)); `JdbcSnapshots`, the newest snapshot per id (spec [0074](specs/0074-a-recovery-that-does-not-replay-everything.md)); a `JournalFeed` with `JdbcOffsets` for read models (spec [0075](specs/0075-a-read-model-that-follows-the-journal.md)); and `ShardedJournal` and `ShardedSnapshots`, which split it across databases by id (spec [0088](specs/0088-a-journal-across-databases.md)). The [cluster guide](docs/cluster.md) shows it in use | nothing |
| `lark-actor-projection` | `lark-actor`'s journal as a lark-stream `Stream`: `Projection.follow` reads every event of one kind from a `JournalFeed`, polling once caught up, and `runProjecting()` stores each event's offset once its work is done, so a read model resumes where it left off (spec [0075](specs/0075-a-read-model-that-follows-the-journal.md)); with `Prune.after`, snapshots delete only what the named read models have already read (spec [0077](specs/0077-pruning-that-waits-for-read-models.md)) | nothing |
| `lark-cluster` | `lark-actor-remote` as a cluster with no coordinator: seeds from a list, DNS or SRV, membership agreed by SWIM gossip over the same transport, member events, and a partition resolved by majority, quorum or lease, with the losing side downing itself first (spec [0069](specs/0069-nodes-that-agree-who-is-up.md)); entities and singletons placed on whichever member owns them, and moved between members without ever running twice (spec [0070](specs/0070-an-entity-on-whichever-node-owns-it.md)). Benchmarks against Pekko Cluster, three nodes a side, cover sharded tell and ask, a persistent append, reliable and durable sends, and a topic's fan-out; the results are still waiting on a run on the machine the other numbers came from ([numbers](lark-actor-benchmarks/README.md)). The [cluster guide](docs/cluster.md) shows it in use | nothing |
| `lark-cluster-kubernetes` | `lark-cluster` on Kubernetes: seeds from the pods API, and a `Lease` object to break an even split | `io.fabric8:kubernetes-client` |
| `lark-cluster-aws` | `lark-cluster` on AWS: seeds from Cloud Map or ECS, and a DynamoDB item as the lease that breaks an even split | `software.amazon.awssdk` (`servicediscovery`, `ecs`, `dynamodb`) |
| `lark-app-actor` | a `lark-actor` flock as a node that lives as long as the application, and an actor as a node keyed by the `ActorRef<M>` of its protocol (spec [0062](specs/0062-actors-that-find-each-other.md)) | nothing |
| `lark-app-cluster` | a `lark-cluster` membership as a node, joined as a HOCON section describes: the backend is found by name on the classpath, its client is closed after the node leaves, and a node that the others downed ends its process (spec [0096](specs/0096-a-cluster-joined-from-config.md)) | `lark-app-typesafe` |
| `lark-app-liquibase` | a changelog as a node, which reading the database depends on | `liquibase-core` |
| `lark-app-typesafe` | a HOCON section as a node, every fault reported at once, and a setting that picks a module | `com.typesafe:config` |
| `lark-otel` | a `Context` that crosses a fork, so a trace survives a `parMap` | `opentelemetry-api` |
| `lark-slf4j` | lark's own log lines through the backend a service already has configured, with annotations in the MDC | `slf4j-api` |
| `lark-micrometer` | counters, gauges and histograms into the `MeterRegistry` a service already has | `micrometer-core` |
| `lark-app-gradle` | a Gradle plugin that checks and draws every graph in a project as it compiles, and hands `lark-test`'s colour settings to every test task | `gradleApi()` |

A commit after the latest tag builds the next patch version as a `-SNAPSHOT`
(after `v0.5.0`, `0.5.1-SNAPSHOT`), which is what
`./gradlew publishToMavenLocal` installs.

One module is built and tested but not published. `lark-structured` has
`parZip`, `parMap`, `raceN` and `timeout`, with the same names and shapes as
lark's, and runs each call's branches in the JDK's `StructuredTaskScope`. They
show up under their caller in a thread dump and inherit `ScopedValue` bindings.
That API is a preview in JDK 27, and the module will be published when JDK 28
makes it final ([0040](specs/0040-a-flock-the-jdk-can-see.md)).

`lark-bank` is not published either, because it is an application to run.
`./gradlew :lark-bank:run` starts three nodes in one JVM with accounts and
transfers sharded across them. It serves a page for sending money at
<http://localhost:8081>, and an admin page at `/admin` that streams the
cluster's numbers and has a load button and a "crash n3" button
([0094](specs/0094-a-bank-you-can-watch.md)).

Before, on `arrow-fx-coroutines`:

```kotlin
suspend fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

After, on lark. `Err`, `Dashboard`, `users` and `orders` belong to the service,
and these are all the imports the function needs:

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.parZip

fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

`parZip` takes from two to nine branches, as in `arrow-fx-coroutines`. The
other combinators, one line each:

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

`flock { }` is the scope that `async` forks in. It is the one piece here that
`arrow-fx-coroutines` has no equivalent name for. A `Deferred` that nobody
awaits is still joined when the scope closes, and if it raised, that raise is
still the block's `Left`. Outside any `Raise`, `parZip`, `parMap` and `raceN`
take plain `() -> A` branches and return the combined value.

The first branch to raise or throw interrupts its siblings; `raceN` interrupts
the losers. Interrupt is the only cancellation the JDK has, so a cancelled
branch stops at its next interruptible blocking call, and a combinator returns
only once every fork it opened has ended.

A fork can also wait until it is needed. `async(start = Lazy) { }` does not
reach the executor until something awaits it, so work that a later branch
decides it doesn't want is never done. A lazy fork that nobody awaits never
runs at all, which means its raise can never become the block's `Left` the way
an eager fork's would. The flag is set per call rather than per scope on
purpose: a scope where nothing started until it was awaited would turn a
fan-out into sequential code, and preventing that is what `flock` is for.

`cancel()` does the same kind of stop, but aimed by hand at one fork rather than
by a combinator at a branch. It interrupts the fork and returns once that fork
has ended, so nothing it owns is still running afterwards. A cancelled fork's
outcome counts as handled, so the scope does not answer with it, even if the
fork raised before the interrupt arrived. If a body swallowed the interrupt and
returned anyway, it still has its value, and `await()` after `cancel()` returns
it.

Every forking combinator also takes the executor to fork on, before its
branches: `parZip(on = pool, { … }, { … }) { … }`, `flock(on = pool) { }`,
`timeout(on = pool, 2.seconds) { }`. A call that doesn't name one gets a new
virtual thread per fork, as above. A fork clears the interrupt flag as its body
exits, so a cancelled branch never passes the flag on to whatever the executor
runs next.

## Pekko

A Pekko application already has one place that names, sizes and instruments its
threads (the dispatcher config), and `lark-pekko` makes a dispatcher there the
executor lark forks on:

```kotlin
dependencies {
    // lark and pekko-actor come with it; nothing else does
    implementation("io.github.matthewjones372:lark-pekko:0.9.0")
}
```

The dispatcher is configured alongside Pekko's own, and named by the part after
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

`larkDispatcher` rejects a dispatcher whose executor is `fork-join-executor` or
`thread-pool-executor`, and names `pekko.actor.<id>.executor` in the error. A
lark fork blocks, and blocking the pool Pekko sizes for its actors causes the
starvation Pekko's own documentation warns about. `virtual-thread-executor` is
accepted, and so is `type = PinnedDispatcher`, whose thread is not shared with
anything else. Both the classic `ActorSystem` and the typed one are a
`ClassicActorSystemProvider`, so either can use the extension.

Code forking on that dispatcher gets a `CompletionStage` back from most of
Pekko's APIs (an HTTP request, an `ask`, a stream run into a sink), and
`await()` is how a fork waits for one:

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

The fork that called `await()` is the thing that parks, and on a virtual thread
that doesn't tie up a carrier. A stage that failed rethrows the original cause
rather than the `CompletionException` or `ExecutionException` wrapped around
it, so the body can catch the exception it declared. An interrupt (from a
closing scope, a `raceN` loser or a `timeout`) cancels the stage with
`cancel(true)` and rethrows the `InterruptedException`, so a value that arrives
later is dropped instead of being delivered to nobody. Pekko's Scala futures
support `await()` too.

`await()` does not change what a stage holds. A stream run into `Sink.seq`
still buffers every element before it completes, so awaiting it means waiting
for the whole collection, not reading a stream.

## Collecting every error

`parZipOrAccumulate` and `parMapOrAccumulate` run every branch to completion and
return all of the raises instead of only the first. This suits something like
form validation, where you want every problem at once. The scope declares a
`NonEmptyList` of the branches' error type, and these are all the imports:

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

Both also have a form that takes `combine: (Err, Err) -> Err` as the first
argument, for a scope that declares a single error type and knows how to merge
two errors into one. Errors are returned in branch order (or the iterable's
order), regardless of which branch raised first. A thrown exception is not
accumulated: it stops the other branches the same way `parZip` does, and the
same exception instance is rethrown.

## Resources

`resourceScope { }` runs a block with resources acquired inside it, and releases
them in reverse order of acquisition when the block exits, whether by return,
raise, throw or interrupt. `install` takes the acquire and release pair, and the
release is told which of those happened:

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
an interrupt, and the `either` it exits through returns a value to its caller,
so from the scope's point of view nothing failed. If a release throws when
nothing else has failed, that exception is what the caller sees; if it throws
after another failure, it is added as suppressed. Either way, the remaining
releases still run.

## Retries, timeouts and policies

A `Schedule<Input, Output>` is a value that answers each input with a decision:
continue after a delay, or stop. `retry` feeds it what the action threw, and
`repeat` feeds it what the action returned. The delays are spent on the calling
virtual thread, so an interrupt ends the schedule where it is.

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

// bounded by time on the inherited clock rather than by tries, so a slow try does not stretch it
val patiently = Schedule.spaced<Throwable>(20.milliseconds) zipLeft Schedule.upTo(5.seconds)

fun drained(): Unit = patiently.retry { check(outbox.unsent() == 0L) }

// the same over a declared error: the last raise is the Left
fun row(id: Id): Either<Err, Row> = Schedule.recurs<Err>(5).retryRaise { rows.find(id).bind() }

fun quote(id: Id): Either<Err, Quote> = either {
    timeout(2.seconds) { slow.quote(id).bind() }              // throws TimeoutException
}

fun maybe(id: Id): Either<Err, Quote?> = either {
    timeoutOrNull(2.seconds) { slow.quote(id).bind() }        // null instead
}
```

`recurs`, `spaced`, `upTo`, `exponential`, `linear`, `fibonacci`, `forever`,
`identity`, `doWhile` and `doUntil` build a schedule; `and`, `or`, `andThen`,
`zipLeft`, `zipRight`, `map`, `collect`, `delayed` and `jittered` combine them.
`timeout` is a `raceN` against a sleeper with the loser interrupted, so a block
that doesn't finish in time stops at its next interruptible call instead of
being left running.

A `policy` puts these into one chain, which is checked when it is built and
applied as a whole on every call ([spec 0110](specs/0110-guards-that-compose.md)).
A `deadline` is one time budget for the call, retries included. Retrying stops
before a delay the deadline couldn't cover, and each attempt is limited to the
time that is left. Steps in the wrong order throw `IllegalArgumentException`
when the policy is built.

A `CircuitBreaker` is added with `guard(breaker)`. After `maxFailures` failures
in a row it rejects every call until the next delay of its `resetAfter`
schedule has passed, then lets one trial call through
([spec 0111](specs/0111-a-breaker-that-stops-calling.md)). A `Bulkhead` is
added with `guard(bulkhead)`: at most `maxConcurrent` callers are inside at
once, and the rest wait up to `maxWait` (limited by the deadline) before being
rejected ([spec 0112](specs/0112-a-bulkhead-that-caps-the-callers.md)). A
`RateLimiter` is added with `guard(limiter, cost)`. It is a token bucket refilled
at `rate` every `per`; a call without a token waits for its reserved token up
to `maxWait` (limited by the deadline), and is rejected with its `retryAfter`
if the wait would be longer
([spec 0113](specs/0113-a-limiter-that-paces-the-calls.md)).

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.guarded
import io.github.matthewjones372.lark.policy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val partner = policy("partner-api") {
    deadline(2.seconds)
    retry(Schedule.exponential<Throwable>(100.milliseconds).jittered() zipLeft Schedule.recurs(3))
    attemptTimeout(500.milliseconds)
}

fun quote(id: Id): Quote = partner { http.quote(id) }

// a refusal by any guard in the chain, as a declared error
fun quoted(id: Id): Either<Err, Quote> = either {
    guarded(partner, ifRejected = { Err.Unavailable }) { http.quote(id) }
}
```

The minimum JDK is 25. Before JDK 24, a blocking call inside a `synchronized`
block pins its carrier thread instead of parking it, so a service on JDK 21 can
run out of carriers. This did happen: lark-bank's load generator deadlocked on
21 with every carrier pinned inside Jackson's serializer cache. JEP 491 removed
that pinning in 24, and 25 is the LTS release that includes it. The Gradle
plugin and the compiler plugin still compile for 21, because they run inside
whatever JDK the build tools use.

## Status

Experimental, as described [above](#what-this-is): everything below works and
is tested, and none of it is settled.

`flock { }`, `async`/`await`, `parZip`, `parMap`, `raceN`,
`parZipOrAccumulate`/`parMapOrAccumulate`, `resourceScope`, `Schedule` and
`timeout` are implemented. That covers everything from
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md) and
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md) that
was kept, and all of
[`specs/0003-a-drop-in-for-arrow-fx.md`](specs/0003-a-drop-in-for-arrow-fx.md).

So are `LarkLocal`, the bound `Clock` and the log a fork carries
([0017](specs/0017-what-a-fork-inherits.md),
[0018](specs/0018-the-log-a-fork-carries.md)), and the application graph in
`lark-app` and `lark-app-pekko`
([0016](specs/0016-an-application-that-starts-as-a-value.md),
[0019](specs/0019-what-a-service-reads-from-outside-itself.md),
[0020](specs/0020-an-actor-is-a-node.md)).

[`AGENTS.md`](AGENTS.md) describes how work on this repository is done.

## Related projects

- [Kestrel](https://github.com/matthewjones372/kestrel): load simulations on
  virtual threads.
- [Dipper](https://github.com/matthewjones372/dipper): a stream with a typed
  failure, over Pekko Streams. It is now the `lark-stream` module here, brought
  in with its history.

## What a fork inherits

By default a fork inherits nothing: `Thread.ofVirtual().start(command)` gives
the body a thread with no knowledge of the one that started it. A `LarkLocal`
is what carries values across. It is read on the opening thread and rebound
inside the task, so a value bound outside a `parMap` can be read in every
branch, whether that runs on a Pekko dispatcher or on one of lark's own virtual
threads.

```kotlin
import io.github.matthewjones372.lark.larkLocal
import io.github.matthewjones372.lark.parMap

val requestId = larkLocal { "none" }

requestId.locally("abc-123") { parMap(rows) { row -> requestId.get() } }   // every branch reads it
```

The clock is the first thing in lark that uses it. `Schedule` waits on whichever
`Clock` the thread inherited, so a test of a backoff doesn't have to actually
wait:

```kotlin
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.retry
import kotlin.time.Duration.Companion.seconds

val backoff = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(5)

clock.locally(fixedClock()) { backoff.retry { flaky.row(id) } }            // microseconds, not minutes
```

`TestClock` is the other test clock: it only moves when the test moves it.
`adjustWhenBlocked` waits until every sleep is waiting on a time still in the
future before moving, because nothing here can tell that a fork has reached its
`sleep` the way a fiber runtime can.

The log uses the same binding, which is why a correlation id survives a fork
when an MDC value does not:

```kotlin
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan

logAnnotated("correlation_id" to request.id) {
    logSpan("register") { parMap(request.items) { item -> logInfo("checking $item") } }
}
```

`capturingLogs { }` binds a logger that a test can read, so assertions about
logging are assertions about values.

Where those lines go is decided by a dependency rather than a call. If nothing
is bound, they go to stderr, which is enough to watch a service start. Add
`lark-slf4j` to the classpath and they go wherever the rest of the service
already logs:

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-slf4j:0.9.0")
}
```

It registers itself through a `ServiceLoader`, so there is nothing to add to
`main`. The annotations arrive as MDC entries rather than as text appended to
the message, so a correlation id survives the fork and also reaches
`%X{correlation_id}`, a JSON encoder and a field search. Appending it to the
message would survive the fork but none of those could read it.

`logger.locally(MyLogger()) { … }` still takes priority, for a test, your own
backend, or a single block whose output should go somewhere else.

Metrics work the same way. There is nothing to declare, nothing to pass through
the graph, and no node that takes a registry:

```kotlin
counter("petshop.adoptions").increment()
gauge("petshop.queue.depth").set(waiting.toDouble())
timed("petshop.adopt") { shop.adopt(id, by) }

metricTagged("species" to "tortoise") { counter("petshop.adoptions").increment() }
```

`metricTagged` uses the same binding as the log, so a tag applies to every
measurement a fork takes inside it. It is deliberately separate from the
annotations `logAnnotated` binds: those are meant to be unique, and a tag with
unbounded values creates one time series per request. Add `lark-micrometer` to
the classpath and the numbers go to whatever registry the service already has.
If nothing is bound they are not recorded anywhere, which costs nothing and
throws nothing. `capturingMetrics { }` is how a test reads them.

## Applications

`lark-app` represents a dependency graph as a value. A recipe names what it
builds and takes what it needs as parameters, so the graph is data before
anything runs. `findings` reports what is wrong before any recipe is called,
`subgraph` gives a test four nodes instead of forty, `overriding` rejects a fake
under a key that nothing asked for, and starting the graph runs one topological
layer at a time on lark's forks, with releases in reverse topological order.

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

Because the graph is a value, it can also be drawn. `render()` returns Mermaid,
and `larkWiring` writes one diagram per application:

![A wiring graph: Tuning above Database and Memo, Database above Accounts and
Postings, and Opening, Reporting and Memo all above Frontage.](docs/wiring.png)

`Database` and `Memo` have no edge between them, so they start at the same time.
Every path into `Frontage` is something that must be ready before the service
accepts requests. Neither fact is visible in the code that built the graph.

If a graph declares the node it starts from, a build can read it without
running `main`. That is what `lark-app-gradle` does as the project compiles:

```
lark-app wiring

❯ error: missing DataSource
❯     for OrderRepo          Wiring.kt:42

❯ warning: nothing reaches KafkaProducer    Kafka.kt:9
```

A node records where it was defined, so a fault names the recipe to edit. A
missing key and a cycle are errors; a key provided twice and a node that no root
reaches are warnings. Applying the plugin is the only setup per project, and it
draws each graph into `build/reports/lark` next to the report.

Configuration gets the same treatment. A hierarchy of HOCON files resolves to
one document, and `lark-app-typesafe` shows which file each value came from and
what it overrode:

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

Values are redacted by default. Matching on the name is the baseline,
`Secrets.and(path)` marks an exact path, and `secret(path)` reads a value that
prints as the mask through `toString`, so a log line cannot leak it either.

A probe is what makes "started" mean "ready" rather than just "constructed".
The same probes answer `/ready` afterwards, through a `HealthRegistry` that a
route takes as a dependency. `lark-app-pekko` makes an actor a node, keyed by
the `ActorRef<T>` of its protocol.

There are no annotations, no annotation processor and no effect type. A module
is an expression, so there is nothing for KSP to read: KSP models declarations,
and `val app = single { } + single { }` is not one. The check runs the
expression instead, which is why it can see an actor node and a module
assembled inside a conditional.

Running the expression means waiting for a build, so `lark-app-compiler` reads
the same graph from the compiler's syntax tree and reports the same message in
the editor:

![IntelliJ underlining `singleOf(::ActorPetShop)` in red, with the tooltip
"lark-app: PetShop needs ActorRef<Shop>, and nothing builds it".](docs/editor-error.png)

It is a K2 checker with no backend part, which is what allows the IDE to run
it. It follows the shapes it knows (the factories above, `+`, `boundTo`, names
in the same compilation unit, the branches of a `when`) and gives up on the
whole application as soon as it meets one it doesn't, because a false error on
working code is worse than a fault reported a moment later. `larkWiring`
remains the real check: it runs the graph, so it sees things no source reader
can.

Two things to know before turning it on. IntelliJ doesn't run third-party
compiler plugins in the editor until
`kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in the registry.
And a module that comes from another Gradle module has no source here to read,
so the editor says nothing about a graph assembled across modules.
[`docs/app.md`](docs/app.md) has the full details.

## Streams

`lark-stream` provides `Stream<E, A>`. The failure a pipeline can end with is
part of the type, an element can never be null, and running a stream returns an
`Exit` that is `Done`, `Failed(e)` or `Died(cause)`, rather than a stage nobody
reads. A `Died` is also logged at error level, naming the operator, the element
and the caller's line that built it, so a pipeline run only for its effects
still reports what happened.

A stream is a description and does not name a backend. The backend is a value
passed in where the run starts. Each backend is in its own module, which brings
`lark-stream` with it:

```kotlin
dependencies {
    // Pekko Streams: lark-stream, Pekko, lark-pekko and arrow-core come with it
    implementation("io.github.matthewjones372:lark-stream-pekko:0.9.0")
    // or a pull loop on one virtual thread, with nothing under it but lark-stream
    // implementation("io.github.matthewjones372:lark-stream-forks:0.9.0")
    // or runs as actors of a lark-actor flock: Actors(flock)
    // implementation("io.github.matthewjones372:lark-stream-actors:0.9.0")

    // time a test owns: tick, groupedWithin and restartOnDefect on a TestClock
    testImplementation("io.github.matthewjones372:lark-stream-test:0.9.0")
}
```

One description, with the backend chosen where it runs:

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

Each returns the same `Exit`. A backend rejects a run it cannot complete before
anything starts, naming the operator and the line that built it. `Forks` runs
nothing that needs a second thread or a clock, and no backend runs another
backend's `Source`.

On `TestStreams`, `tick`, `groupedWithin`, `restartOnDefect`'s delays and
`mapPar` wait only on the test's `TestClock`, and each `adjust` returns once
everything due by then has run, in time order. An hour of one-a-minute ticks is
a single call:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

### Optimisation, rendering and profiling

Before a run starts, adjacent element-at-a-time stages (`map`, `filter`,
`mapOrFail` and similar) are fused into one, a `take` of a `take` becomes one
`take`, and a `catchAll` over a stream that cannot fail is removed. A defect
still names the operator and the line it came from. A five-stage chain on Pekko
went from 150 ns to 91 ns per element.

`render()` draws the description as text or Mermaid, with each stage and the
line that built it, and `render(optimised = true)` draws what actually runs:

```
Stream.from
fused[map, map, filter, map, mapOrFail]  Pipelines.kt:15
runFold                                  Pipelines.kt:20
```

`measured(Measured("ingest", metrics))` reports each stage's element count, the
time spent in its body and the time it waited for demand, through lark's
`Metrics`. A `Profiler` keeps those numbers, and
`render(profile = profiler.profile())` shows where the time went:

```
map          Pipelines.kt:15  3% · 1.0 µs busy · 3.0 µs waiting · 100000 out
map          Pipelines.kt:16  67% · 20.0 µs busy · 3.0 µs waiting · 100000 out
filter       Pipelines.kt:17  3% · 1.0 µs busy · 3.0 µs waiting · 50000 out
```

### On Pekko Streams

`lark-stream-pekko` also has the operators that take Pekko's own types:
`Stream.from(source)` and `toSource()` to convert in and out, `divertLefts` and
`runWith` to a `Sink`, and `run(system)`, which is short for
`run(PekkoStreams(system))`. Handlers and streams use the same building blocks:
an element body is a `Raise`, `mapPar` runs it on a virtual thread, and
`awaitExit` turns the run's `Exit` back into a result in the handler that
started it.

Rows in, receipts to one sink, declines to another, with all the imports it
needs:

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

[`docs/stream.md`](docs/stream.md) covers the rest: the same ingest written
against raw Pekko and against lark-stream side by side, why a missing value is
treated as a failure rather than an empty stream, the operator table, and how a
declared failure is passed along. `lark-stream` used to be a separate library,
dipper, before it moved into this repository.

## Licence

Apache 2.0.

# lark-app

> Lark is a scratchpad, not a finished library — see
> [what this is](../README.md#what-this-is). Everything here works and is tested;
> none of it is settled.

**An application as a value.** A dependency graph you can read, subset,
override and start: `single` names what a recipe builds and takes what it needs
as parameters, so the graph is data before any recipe runs. `validate` answers
what is missing before anything is constructed, `subgraph` gives a test the four
nodes it needs rather than the forty an application has, and starting one runs
a topological layer at a time on lark's forks.

The module is `lark-app` and everything below is in
`io.github.matthewjones372.lark.app`. It depends on `lark`, and on nothing else.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-app:0.1.0")
}
```

[`cookbook.md`](cookbook.md) is the task-shaped version of this page: wiring,
configuration, probes, testing, actors, one recipe each.

## The problem

A service wires itself by hand in `main`, so nested `resourceScope` blocks *are*
its dependency graph: moving a dependency means moving a brace, release order is
right by accident, and nothing starts in parallel because a `resourceScope` body
is one thread. A test that needs one repository starts the whole tree, because
the only way to reach a leaf is to build the trunk.

Nothing here is an effect type or a container. `Resource`, `parMap`, `Schedule`
and `timeout` already do the work; what was missing is a value saying which
service needs which, to point them at.

## A module is a value

```kotlin
import io.github.matthewjones372.lark.app.single

val persistence =
    single { cfg: DbConfig -> install({ HikariDataSource(cfg) }) { ds, _ -> ds.close() } as DataSource } +
    single<UserRepo, DataSource> { ds -> PgUserRepo(ds) } +
    single<OrderRepo, DataSource> { ds -> PgOrderRepo(ds) }

val domain =
    single { users: UserRepo -> RegisterUser(users) } +
    single { users: UserRepo, orders: OrderRepo -> PlaceOrder(users, orders) }

val app = config + persistence + domain + web
```

A node is keyed by the type its recipe returns, and built once however many
things need it: one `DataSource`, both repositories on it. `plus` is override —
the right-hand node wins wherever the two share a key — and the order you write
the modules in means nothing, because matching is by type.

A recipe takes up to nine dependencies, as `parZip` takes nine branches. Past
that, group them into a node of their own.

Where the recipe returns a concrete class and the key should be its interface,
say both:

```kotlin
single<UserRepo, DataSource> { ds -> PgUserRepo(ds) }         // keyed UserRepo
single { ds: DataSource -> PgUserRepo(ds) }                   // keyed PgUserRepo
```

Kotlin has no partial type-argument inference, so it is all the type arguments
or none.

## What the graph can tell you

```kotlin
import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.validate

app.validate()      // Either<NonEmptyList<WiringError>, Plan>
app.render()        // mermaid, in an order a golden file can hold
```

`validate` runs no recipe. It answers with every missing key at once rather than
the first, because a graph is usually short of a module rather than of one node,
and a cycle answers with the path around it rather than the set of keys on it:

```
lark-app wiring error

❯ missing DataSource
❯     for OrderRepo
❯     for UserRepo
```

One test is the whole gate:

```kotlin
@Test
fun `the application wires`() = app.validate().shouldBeRight()
```

## Starting and stopping

```kotlin
import io.github.matthewjones372.lark.app.runApp

fun main() {
    exitProcess(runApp(app) { server: HttpServer -> server.start(); awaitShutdown() }.code)
}
```

A topological layer starts at a time under `parMap`, so independent nodes come
up on forks of their own, the first refusal interrupts its siblings, and lark
guarantees every fork has ended before the combinator returns. What was acquired
before a failure is released.

Releases run in reverse **topological** order rather than reverse acquisition
order: under `parMap` the acquisition order is whichever fork got there first,
so it is not an order to give anything back in.

A recipe that cannot proceed says so rather than throwing:

```kotlin
single { sys: Sys -> sys.required("DB_URL").getOrElse { refuse("DB_URL is not set") } }
```

`Shutdown` is a value, so a test asks an application to stop without raising a
signal; `runApp` puts a JVM hook behind it, and the hook waits for the releases
to finish. What went wrong reaches stderr through `describe()`, and `runApp`
answers with an `ExitCode` rather than ending the process — so a test can run an
application and read what it decided. The `exitProcess` is the caller's.

## Reading the environment

```kotlin
import io.github.matthewjones372.lark.app.RealSys
import io.github.matthewjones372.lark.app.Sys
import io.github.matthewjones372.lark.app.int
import io.github.matthewjones372.lark.app.required

single<Sys> { RealSys }
single { sys: Sys -> AppConfig(sys.required("DB_URL").bind(), sys.int("PORT").bind()) }
```

A node calling `System.getenv` is a node no test can configure. The readers
answer with `Either` — `ConfigError.NotA("PORT", "an Int", "eighty-eighty")` —
because a service reading several names wants every complaint at once, which is
`parZipOrAccumulate`'s shape rather than a raise's.

## Started means ready

```kotlin
import io.github.matthewjones372.lark.app.probe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

single { cfg: KafkaConfig -> install({ KafkaClient.connect(cfg) }) { c, _ -> c.close() } }
    .probe("kafka", timeout = 2.seconds, attempts = 10, interval = 500.milliseconds) { it.assigned() }
```

Dependents wait for the probe, not the recipe. A pool with no connection and a
consumer with no assignment have both been constructed and neither can serve
anything. Each probe is asked inside its own timeout, so a probe that hangs is
not a start that hangs, and the wait between attempts goes through the clock the
thread inherited — under `fixedClock`, ten attempts take microseconds.

The same probes answer afterwards:

```kotlin
import io.github.matthewjones372.lark.app.HealthRegistry

single { health: HealthRegistry -> routes { get("/ready") { health.readiness() } } }
```

`HealthRegistry` is the one key a running application provides itself, so a
route takes it as a dependency rather than being handed the graph. Every probe
is asked at once and each inside its own timeout; `critical = false` degrades
rather than downs, and a node reading `liveness()` on the way up is told the
graph is still coming up.

## Testing

```kotlin
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp

testApp(app.subgraph<UserRepo>().overriding(single<Sys> { FakeSys(mapOf("PORT" to "8080")) })) { repo: UserRepo ->
    repo.find(id).shouldBeRight()
}
```

`subgraph` keeps only what its root is reached through, so a test of one
repository starts a pool and a config rather than a broker and a server.
`overriding` is `plus` that refuses a key the module does not already hold —
plain `plus` would add a node nothing asked for and leave the real one running,
which is a green test that faked nothing. `testApp` gives the graph back
whatever the block did, an assertion failure among them.

Nothing here is a singleton and there is no service locator, which is what makes
all three possible.

## Actors

`lark-app-pekko` makes an actor a node, keyed by the `ActorRef<T>` of its
protocol.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-app-pekko:0.1.0")
}
```

```kotlin
import io.github.matthewjones372.lark.app.pekko.actor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef

val actors =
    single<ActorSystem> { install({ ActorSystem.create("app") }) { system, _ -> system.terminate() } } +
    actor<IngestCommand>("ingest") { repo: UserRepo -> IngestBehavior.create(repo) } +
    actor<ReportCommand>("report") { repo: UserRepo -> ReportBehavior.create(repo) }

single { ingest: ActorRef<IngestCommand>, cfg: HttpConfig -> HttpServer(cfg, ingest) }
```

Two protocols are two keys with no ceremony, and a dependent declares the
protocol it sends rather than a name. The stop is awaited through
`gracefulStop`, so an actor holding a connection has given it back before the
node that opened the connection is released.

## What it does not do

- **No annotations and no annotation processor.** KSP models declarations and a
  module is an expression, so the compile-time report ZIO's macro gives has no
  route here that keeps `plus`, `subgraph` and `render`. `validate` is the gate
  instead. [Spec 0016](../specs/0016-an-application-that-starts-as-a-value.md)
  says so at more length.
- **No local environment.** One graph, one instance per key. Two `DataSource`s
  are two keys — a `@JvmInline value class Replica(val ds: DataSource)` is free
  under the type keys and says which one a consumer wanted.
- **No effect type.** A recipe is a function, a node is a value, and `suspend`
  appears nowhere.

# Building an application

Recipes for `lark-app`, in the order a service meets them. Every Kotlin fence
below is compiled by `CookbookTest` against the library it documents, so a
recipe that stops being true stops the build.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-app:0.1.0-SNAPSHOT")
    implementation("io.github.matthewjones372:lark-app-pekko:0.1.0-SNAPSHOT")  // actors only
}
```

The types the recipes are written against, once:

<!-- cookbook-fixtures -->
```kotlin
import arrow.core.getOrElse
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.app.pekko.actor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Behaviors
import io.github.matthewjones372.lark.ExitCase
import io.github.matthewjones372.lark.app.HealthRegistry
import io.github.matthewjones372.lark.app.RealSys
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.Sys
import io.github.matthewjones372.lark.app.int
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.probe
import io.github.matthewjones372.lark.app.required
import io.github.matthewjones372.lark.app.runApp
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.app.validate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class DbConfig(val url: String)
data class HttpConfig(val port: Int)
data class AppConfig(val db: DbConfig, val http: HttpConfig)

interface Pool { fun healthy(): Boolean }
class Hikari(val config: DbConfig) : Pool {
    override fun healthy() = true
    fun close() = Unit
}

interface UserRepo { fun find(id: Long): String? }
class PgUserRepo(private val pool: Pool) : UserRepo {
    override fun find(id: Long) = "user-$id"
}

class RegisterUser(private val users: UserRepo)

class HttpServer(private val config: HttpConfig, private val register: RegisterUser) {
    fun start() = Unit
    fun stop() = Unit
}
```

## Wire an application

A recipe names what it builds and takes what it needs as parameters. Nothing
runs while the graph is being described.

<!-- cookbook -->
```kotlin
val config: Module =
    single<Sys> { RealSys } +
    single { sys: Sys ->
        AppConfig(
            DbConfig(sys.required("DB_URL").getOrElse { refuse("DB_URL is not set") }),
            HttpConfig(sys.int("PORT").getOrElse { refuse("PORT: $it") }),
        )
    } +
    single { app: AppConfig -> app.db } +
    single { app: AppConfig -> app.http }

val persistence: Module =
    single { db: DbConfig -> install({ Hikari(db) }) { pool, _ -> pool.close() } as Pool } +
    single<UserRepo, Pool> { pool -> PgUserRepo(pool) }

val domain: Module = single { users: UserRepo -> RegisterUser(users) }

val web: Module =
    single { http: HttpConfig, register: RegisterUser -> HttpServer(http, register) }

val app: Module = config + persistence + domain + web
```

Order means nothing — matching is by type. `plus` is override, so the
right-hand node wins wherever two share a key.

## Run it

<!-- cookbook -->
```kotlin
fun main(): Nothing = runApp(app) { server: HttpServer ->
    server.start()
    awaitShutdown()
}
```

Independent nodes start on forks of their own, the first refusal interrupts its
siblings, and everything acquired is released in reverse topological order. A
signal returns from `awaitShutdown`, and the JVM hook waits for the releases
before the process leaves.

## Read configuration, and fail loudly

A node calling `System.getenv` is a node no test can configure. Take a `Sys`.

<!-- cookbook -->
```kotlin
val readingConfig: Module = single { sys: Sys ->
    AppConfig(
        db = DbConfig(sys.required("DB_URL").getOrElse { refuse("DB_URL is not set") }),
        http = HttpConfig(sys.int("PORT").getOrElse { refuse("PORT: $it") }),
    )
}
```

`refuse` ends the start naming this node, which is what reaches stderr through
`describe()`. `sys.int("PORT")` answers `ConfigError.NotA("PORT", "an Int",
"eighty-eighty")` rather than throwing, so several names can be read and every
complaint reported at once.

## Something that has to be given back

<!-- cookbook -->
```kotlin
val closing: Module = single { db: DbConfig ->
    install({ Hikari(db) }) { pool, exit ->
        if (exit is ExitCase.Failure) pool.close() else pool.close()
    } as Pool
}
```

The release is told how the scope ended. Releases run in reverse **topological**
order, not reverse acquisition order — under `parMap` whichever fork won the
race is not an order to give anything back in.

## Two of the same type

One key, one instance. A second `Pool` needs a second key, and a value class is
free under type keys:

<!-- cookbook -->
```kotlin
@JvmInline
value class Replica(val pool: Pool)

val replicated: Module =
    single { db: DbConfig -> Hikari(db) as Pool } +
    single { db: DbConfig -> Replica(Hikari(DbConfig(db.url + "-replica"))) } +
    single { primary: Pool, replica: Replica -> PgUserRepo(replica.pool) as UserRepo }
```

The type says which one a consumer wanted, which a `named("replica")` string
would not.

## Bind an interface

The key is the recipe's return type. Kotlin has no partial type-argument
inference, so it is all the type arguments or none:

<!-- cookbook -->
```kotlin
val boundToInterface: Module = single<UserRepo, Pool> { pool -> PgUserRepo(pool) }

val boundToTheClass: Module = single { pool: Pool -> PgUserRepo(pool) }
```

The first is keyed `UserRepo`, the second `PgUserRepo`. Bind to the interface
when a test will swap it; otherwise the class is honest.

## Something slow to start

Dependents wait for the probe, not the recipe. A pool with no connection has
been constructed and can serve nothing.

<!-- cookbook -->
```kotlin
val probed: Module =
    single { db: DbConfig -> install({ Hikari(db) }) { pool, _ -> pool.close() } as Pool }
        .probe("db", timeout = 2.seconds, attempts = 10, interval = 500.milliseconds) { pool: Pool ->
            pool.healthy()
        }
```

Each probe is asked inside its own timeout, so a probe that hangs is not a start
that hangs. The wait between attempts goes through the bound clock — under
`fixedClock`, ten attempts take microseconds.

## Answer /ready and /live

The same probes answer afterwards. `HealthRegistry` is the one key a running
application provides itself, so a route takes it as a dependency:

<!-- cookbook -->
```kotlin
class Routes(private val health: HealthRegistry) {
    fun ready() = health.readiness()
    fun live() = health.liveness()
}

val routed: Module = probed + single { health: HealthRegistry -> Routes(health) }
```

`critical = false` on a probe degrades rather than downs. A node reading
`liveness()` on the way up is told the graph is still coming up.

## Test one thing, not everything

<!-- cookbook -->
```kotlin
class InMemoryUsers : UserRepo {
    override fun find(id: Long) = "fake-$id"
}

fun aTest() {
    val underTest = app.subgraph<RegisterUser>()
        .overriding(single<UserRepo> { InMemoryUsers() })

    testApp(underTest) { register: RegisterUser -> register }
}
```

`subgraph` keeps only what its root is reached through — four nodes, not forty.
`overriding` refuses a key the module does not already hold, because plain
`plus` would add a node nothing asked for and leave the real one running: a
green test that faked nothing. `testApp` releases whatever the block did, an
assertion failure included.

## Check the graph without running it

<!-- cookbook -->
```kotlin
fun theApplicationWires() = app.validate()

fun theWiringIsWhatWeThink(): String = app.render()
```

`validate` runs no recipe and names every missing key at once, with a cycle
given as the path around it. `render` draws mermaid in an order a golden file
can hold, so an accidental edge shows up as a diff in review.

## Control time, and read the log

Both are lark's, and both cross a fork:

<!-- cookbook -->
```kotlin
fun aTimedTest() = clock.locally(fixedClock()) {
    capturingLogs { logs ->
        logAnnotated("correlation_id" to "abc-123") {
            parMap(listOf(1, 2, 3)) { logInfo("branch $it") }
        }
        logs.all()
    }
}
```

Every line carries the correlation id, including the ones written on a fork,
because the binding is captured where the fork is opened. `TestClock` is the
other clock: it moves only when a test moves it, and `adjustWhenBlocked` waits
until every sleep is on a time still ahead before moving.

## Spawn an actor

<!-- cookbook-pekko -->
```kotlin
data class Ingest(val line: String)

fun ingesting(users: UserRepo): Behavior<Ingest> =
    Behaviors.receive(Ingest::class.java)
        .onMessage(Ingest::class.java) { Behaviors.same() }
        .build()

val actors: Module =
    single<ActorSystem> { install({ ActorSystem.create("app") }) { system, _ -> system.terminate() } } +
    single { pool: Pool -> PgUserRepo(pool) as UserRepo } +
    actor("ingest") { users: UserRepo -> ingesting(users) }

val sending: Module = single { ingest: ActorRef<Ingest> -> Routes2(ingest) }

class Routes2(private val ingest: ActorRef<Ingest>) {
    fun accept(line: String) = ingest.tell(Ingest(line))
}
```

The protocol is inferred from the behaviour's own type. Write it out —
`actor<Ingest>(…)` — only where the actor takes no dependencies, because with
one type argument given, the overload that takes a dependency cannot apply.

An actor is keyed by the `ActorRef<T>` of its protocol, so two protocols are two
keys and a dependent declares what it sends. The stop is awaited through
`gracefulStop`, so an actor has given back what it held before the node that
opened it is released.

## What to reach for

| Want | Recipe |
|---|---|
| a value built once and shared | `single { … }` — every dependent gets the same instance |
| a value that must be closed | `install(acquire) { held, exit -> … }` |
| a start that can decline | `refuse("why")` |
| two of one type | a `@JvmInline value class` wrapper |
| more than nine dependencies | group them into a node of their own |
| readiness | `.probe(name, timeout, attempts, interval) { … }` |
| a fake in a test | `.overriding(single<T> { … })`, never plain `plus` |
| a smaller test | `.subgraph<Root>()` |
| no waiting in a test | `clock.locally(fixedClock()) { … }` |
| the wiring in review | `render()` against a golden file |

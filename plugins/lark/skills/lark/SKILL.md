---
name: lark
description: Use whenever code depends on lark (io.github.matthewjones372:lark* in a build file, or an io.github.matthewjones372.lark import) before writing a scheduler, a background job, a retry or backoff, a circuit breaker, a test wait, a config read, a secret, a resource's close, a database migration, a LarkApp's run, logging, metrics or tracing. lark already ships each of these; this says which call to use instead of writing it again.
---

# lark

lark is structured concurrency, resources, streams, actors and application wiring
on the JDK's virtual threads, with Lark's own `Fail` and `Either` as the error model.
Blocking is fine everywhere: every fork is a virtual thread, so there is no
`suspend`, no dispatcher and no `CompletableFuture` to reach for.

Before writing any of the code below, use the lark call beside it.

## Before you write it

| About to write | Use instead |
|---|---|
| `object Main { fun main() … }` wiring things by hand | a `Module` graph and `object App : LarkApp<Root>()`, run by `runApp(App)` |
| `run` that calls `server.block()` or `join()` | `awaitShutdown()`; the graph's release stops the server after `run` returns |
| `{ x -> x.close() }` as a node's release | `closingOf(::Thing)` |
| a class that closes what it built | each resource as its own node; the graph releases them in reverse order |
| `Executors.newScheduledThreadPool`, `Timer`, a polling thread | `Stream.tick(every)` started on the graph's `StreamBackend` (`Forks()`) |
| a retry loop, `Thread.sleep` between attempts | `Schedule.exponential(…)` or `Schedule.spaced(…)`, in a `policy { retry(…) }` |
| a down-detector, a failure counter around a client | `policy("…") { guard(CircuitBreaker("…", maxFailures, resetAfter)) }` |
| a stream that dies on one bad dependency call | `restartOnDefect(schedule)` |
| `ConfigFactory.load().getString(…)` in a node | `config<Settings>("section") { Settings(string(…), duration(…)) }` |
| `string("password")` | `secret("password")`, a `Secret` that prints as a mask |
| `if (config.getString("kind") == …)` choosing a module | `choice<Settings, Provided> { chosen -> … }` (unreleased, on `main`), or `conf.choosing("section", read) { chosen -> … }` |
| `CREATE TABLE IF NOT EXISTS` at start-up | `migrations("db/changelog.sql")` from `lark-app-liquibase`; readers take a `Migrated` |
| a startup wait for a dependency | `.probe(name, timeout, attempts, interval) { … }` |
| a logger or `MeterRegistry` passed into a class | `logInfo(…)`, `counter(…).increment()`, `timed(…) { … }`; put `lark-slf4j` and `lark-micrometer` on the classpath |
| MDC or a trace context carried by hand across threads | `logAnnotated(…) { … }`; `lark-otel` on the classpath |
| `Thread.sleep` in a test | `eventually(within) { … }` from `lark-test` |
| a test of something on a timer | `TestStreams` and `TestClock` from `lark-stream-test` |
| a fake swapped in by hand | `.overriding(single<T> { … })`, and `.subgraph<Root>()` for a smaller graph |
| `runCatching` | catch the exception you expect, by name, or `fail` with a declared failure |

Apply the wiring plugin, `id("io.github.matthewjones372.lark.wiring")`. It checks
every graph as it compiles and warns on four of these by name:
`LARK_RUN_NEVER_RETURNS_ON_SHUTDOWN`, `LARK_SCHEDULER_BY_HAND`,
`LARK_SECRET_AS_STRING` and `LARK_SLEEP_IN_TEST`. Fix the code rather than
suppressing one, unless the call really means it.

## Modules

All on Maven Central as `io.github.matthewjones372:<module>`, on one version.

- `lark`: forks, resources, `Schedule`, `timeout`, `policy`, `Clock`, `TestClock`, logs and metrics, `capturingLogs`, `capturingMetrics`.
- `lark-pekko`: a Pekko dispatcher as the executor.
- `lark-stream`: `Stream<E, A>`, described once and run on a backend.
- `lark-stream-forks`: the usual backend, a pull loop on a virtual thread.
- `lark-stream-pekko`: the Pekko Streams backend.
- `lark-stream-actors`: the `lark-actor` backend.
- `lark-stream-test`: `TestStreams` and `TestClock`.
- `lark-test`: `story`, `eventually`.
- `lark-junit`: `Suite` objects run by a JUnit Platform engine.
- `lark-app`: the graph, `LarkApp`, `closingOf`, probes, `testApp`.
- `lark-app-typesafe`: `config`, `secret`, `choosing`, `overridingConfig`.
- `lark-app-kotlinx`: `config<A>(path)` for a `@Serializable` class, no block.
- `lark-app-liquibase`: `migrations` and `Migrated`.
- `lark-app-openfeature`: `openFeature { provider }`, a flag provider as a node.
- `lark-app-openfeature-flagd`: `flagdFlags(path)`, flags in the service's HOCON.
- `lark-app-pekko`: a Pekko typed actor as a node.
- `lark-app-actor`: a `lark-actor` flock and actors as nodes.
- `lark-app-cluster`: a cluster membership as a node.
- `lark-app-gradle`: the wiring plugin.
- `lark-app-compiler`: the checker behind the wiring plugin.
- `lark-otel`: traces across forks.
- `lark-openfeature`: OpenFeature's evaluation context across forks and messages.
- `lark-arrow`: Lark's types to and from Arrow's.
- `lark-assert`: checks for tests, `shouldBe` to `shouldBeRight`, failing at the test's line.
- `lark-slf4j`: log lines through SLF4J.
- `lark-micrometer`: metrics on Micrometer.
- `lark-kafka`: Kafka as a `Stream`, commit after the work, dead letters, transactions.
- `lark-kafka-pekko`: `lark-kafka` through Pekko's connector.
- `lark-actor`: actors as values on virtual threads.
- `lark-actor-journal-jdbc`: an actor's journal on JDBC.
- `lark-actor-projection`: a journal as a resumable `Stream`.
- `lark-actor-remote`: actors across nodes.
- `lark-actor-remote-protobuf`: remote messages in Protobuf.
- `lark-actor-remote-avro`: remote messages in Avro.
- `lark-actor-remote-kotlinx`: remote messages from `@Serializable` classes.
- `lark-cluster`: gossip membership and downing.
- `lark-cluster-kubernetes`: cluster seeds and a Lease on Kubernetes.
- `lark-cluster-aws`: cluster seeds and a lease on AWS.

## More

`llms.txt` at https://github.com/matthewjones372/lark/blob/main/llms.txt has the
facts, the full table and links to every guide; `docs/cookbook.md` has one
recipe per application task, and `docs/core.md` covers retries, timeouts and
policies.

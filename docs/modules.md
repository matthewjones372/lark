# Modules

Lark is split into modules so that a service depends only on what it uses.
Each module brings the Lark modules it builds on and, at most, the one
external library its purpose needs. A `NoOtherDependenciesTest` in every
module checks its runtime classpath against that list, so an unexpected
transitive dependency fails the build.

## Requirements and coordinates

- **JDK 25 or newer** at runtime. The libraries are compiled for 25; see
  [why](core.md#requirements-and-limitations).
- **Kotlin**: built with Kotlin 2.4.10.
- **Group**: `io.github.matthewjones372`. Every published module shares one
  version.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark:0.10.1")
    implementation("io.github.matthewjones372:lark-app:0.10.1")
    testImplementation("io.github.matthewjones372:lark-test:0.10.1")
}
```

Releases are tagged `vX.Y.Z` and published to Maven Central. The guides
describe the `main` branch, which can be ahead of the latest release; features
not yet released are marked where they are documented.

**Snapshots.** An untagged commit builds as the next patch version with
`-SNAPSHOT` (after `v0.10.1`, `0.10.2-SNAPSHOT`). Snapshots are published by
hand rather than on every push, to
`https://central.sonatype.com/repository/maven-snapshots/`, and expire after
90 days. To try `main` reliably, build it locally:

```bash
./gradlew publishToMavenLocal
```

and add `mavenLocal()` to the consuming build's repositories.

## Choosing modules

Start from the core and add the pieces a service needs:

- **Any service:** `lark`. Add `lark-pekko` only to run forks on a Pekko
  dispatcher.
- **Wiring and lifecycle:** `lark-app`, plus the integrations for what the
  service uses (`lark-app-typesafe`, `lark-app-kotlinx`, `lark-app-liquibase`,
  `lark-app-pekko`, `lark-app-actor`, `lark-app-cluster`). Apply the Gradle plugin from
  `lark-app-gradle` to check graphs at build time.
- **Streams:** `lark-stream` for the description, and one backend:
  `lark-stream-forks` unless you already run Pekko Streams. Kafka adds
  `lark-kafka`.
- **Actors:** `lark-actor`. Add `lark-actor-journal-jdbc` for a durable
  journal, `lark-actor-projection` for read models, `lark-actor-remote` and a
  codec module for a second node, and `lark-cluster` for membership and
  sharding.
- **Observability:** `lark-slf4j`, `lark-micrometer`, `lark-otel`, each only
  if the service uses that backend.
- **Tests:** `lark-test` and, for streams, `lark-stream-test`.

## All modules

"Lark deps" are the Lark modules a module brings with it. "Adds" is the
external library it brings beyond those, with the version it is built
against; the library's own transitive dependencies come with it.

### Core

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark` | forks, errors, resources, schedules, policies, context, logs, metrics, `await` on any future ([guide](core.md)) | none | `arrow-core` 2.1.2 | yes |
| `lark-pekko` | a Pekko dispatcher as the executor; `await` on Pekko's Scala futures ([guide](core.md#running-forks-on-pekko)) | `lark` | `pekko-actor` 1.2.1 | yes |
| `lark-structured` | `parZip`, `parMap`, `raceN`, `timeout` on the JDK's `StructuredTaskScope` (preview in JDK 27) | `lark` | none | no |

### Applications

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark-app` | an application as a checked dependency graph ([guide](app.md)) | `lark` | none | yes |
| `lark-app-typesafe` | HOCON sections as nodes, every fault at once, where each setting came from ([cookbook](cookbook.md#configuration-a-module-owns)) | `lark-app` | `com.typesafe:config` 1.4.3 | yes |
| `lark-app-kotlinx` | a HOCON section read from its `@Serializable` class ([cookbook](cookbook.md#configuration-a-module-owns)) | `lark-app-typesafe` | `kotlinx-serialization-core` 1.11.0 | yes |
| `lark-app-liquibase` | a changelog as a node that readers of the database depend on ([cookbook](cookbook.md#run-migrations-as-a-step)) | `lark-app` | `liquibase-core` 4.32.0 | yes |
| `lark-app-pekko` | a Pekko typed actor as a node ([cookbook](cookbook.md#spawn-a-pekko-actor)) | `lark-app`, `lark-pekko` | `pekko-actor-typed` 1.2.1 | yes |
| `lark-app-actor` | a `lark-actor` flock and actors as nodes ([cookbook](cookbook.md#spawn-a-lark-actor)) | `lark-app`, `lark-actor` | none | yes |
| `lark-app-cluster` | a cluster membership as a node, joined from HOCON ([guide](cluster.md#joined-from-config)) | `lark-app-actor`, `lark-app-typesafe`, `lark-cluster` | none beyond Typesafe Config | yes |
| `lark-app-gradle` | the Gradle plugin `io.github.matthewjones372.lark.wiring` ([guide](app.md#checking-the-graph-in-the-build)) | none | Gradle API | yes |
| `lark-app-compiler` | the K2 compiler checker the plugin applies ([guide](app.md#checking-the-graph-in-the-editor)) | none | the Kotlin compiler, already present | yes |

### Streams

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark-stream` | `Stream<E, A>` as a description ([guide](stream.md)) | `lark` | none | yes |
| `lark-stream-forks` | the backend that runs a stream as a pull loop on a virtual thread | `lark-stream` | none | yes |
| `lark-stream-pekko` | the backend on Pekko Streams, and operators that take Pekko's types | `lark-stream`, `lark-pekko` | `pekko-stream` 1.2.1 | yes |
| `lark-stream-actors` | the backend that runs a stream as `lark-actor` actors | `lark-stream-forks`, `lark-actor` | none | yes |
| `lark-stream-test` | `TestStreams`, a backend on a `TestClock` | `lark-stream-forks` | none | yes |
| `lark-stream-parity` | tests only: one suite of pipelines every backend must pass | | | no |
| `lark-stream-benchmarks` | JMH benchmarks of the backends ([results](../lark-stream-benchmarks/README.md)) | | JMH | no |

### Kafka

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark-kafka` | a topic as a `Stream` on any backend, producers, transactions ([guide](kafka.md)) | `lark-stream` | `kafka-clients` 4.3.0 | yes |
| `lark-kafka-pekko` | the same through Pekko's Kafka connector, on Pekko Streams | `lark-kafka`, `lark-stream-pekko` | `pekko-connectors-kafka` 1.1.0 | yes |
| `lark-kafka-benchmarks` | JMH benchmarks against the Java client ([results](../lark-kafka-benchmarks/README.md)) | | JMH, embedded-kafka | no |

### Actors and clusters

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark-actor` | actors, supervision, timers, routing, persistence, actor tests ([guide](actors.md)) | `lark` | none | yes |
| `lark-actor-journal-jdbc` | the journal, snapshots and read-model offsets on JDBC, tables as a Postgres changelog ([guide](cluster.md#state-that-survives)) | `lark-actor` | none (bring your own driver and pool) | yes |
| `lark-actor-projection` | the journal as a `Stream`, for read models that resume ([guide](cluster.md#state-that-survives)) | `lark-actor`, `lark-stream` | none | yes |
| `lark-actor-remote` | actors across nodes over TCP, optional mutual TLS ([guide](cluster.md#two-nodes)) | `lark-actor` | none | yes |
| `lark-actor-remote-protobuf` | message codecs from Protobuf generated classes | `lark-actor-remote` | `protobuf-java` 4.36.2 | yes |
| `lark-actor-remote-avro` | message codecs from Avro specific records | `lark-actor-remote` | `avro` 1.12.2 | yes |
| `lark-actor-remote-kotlinx` | message, event and state codecs from `@Serializable` classes | `lark-actor-remote` | `kotlinx-serialization-protobuf` 1.11.0 | yes |
| `lark-cluster` | membership, downing, sharding, singletons, reliable delivery, topics ([guide](cluster.md#a-cluster)) | `lark-actor-remote` | none | yes |
| `lark-cluster-kubernetes` | seeds from the pods API, and a `Lease` for an even split | `lark-cluster` | `io.fabric8:kubernetes-client-api` 7.9.0 | yes |
| `lark-cluster-aws` | seeds from Cloud Map or ECS, and a DynamoDB lease | `lark-cluster` | AWS SDK 2.55.6 `servicediscovery`, `ecs`, `dynamodb` | yes |
| `lark-actor-benchmarks` | JMH benchmarks against Pekko ([results](../lark-actor-benchmarks/README.md)) | | JMH, Pekko | no |

### Observability and testing

| Module | For | Lark deps | Adds | Published |
|---|---|---|---|---|
| `lark-slf4j` | log lines through SLF4J, annotations in the MDC ([guide](observability.md#logs)) | `lark` | `slf4j-api` 1.7.36 | yes |
| `lark-micrometer` | metrics into a Micrometer registry ([guide](observability.md#metrics)) | `lark` | `micrometer-core` 1.12.0 | yes |
| `lark-otel` | OpenTelemetry context across forks, and spans ([guide](observability.md#traces)) | `lark` | `opentelemetry-api` 1.51.0 | yes |
| `lark-arrow` | Lark's `Either`, `NonEmptyList` and `Fail` to and from Arrow's | `lark` | `arrow-core` 2.1.2 | yes |
| `lark-assert` | `shouldBeRight` and `shouldBeLeft` for Lark's `Either`, failing at the test's own line | `lark` | `opentest4j` 1.3.0 | yes |
| `lark-test` | `story` and `eventually` ([guide](testing.md#stories-and-waiting)) | `lark` | none | yes |
| `lark-junit` | `Suite` objects whose tests share one `Resource` and return `Expectations`, run by a JUnit Platform engine | `lark`, `lark-assert` | `junit-platform-engine` 6.1.3 | yes |

### Example

| Module | For | Published |
|---|---|---|
| `lark-bank` | an example application; see [below](#the-example-application) | no |

## Stability

Lark is experimental and versioned `0.x`. Any release can change or remove
public API, and there is no binary-compatibility check in the build. Pin an
exact version, and expect to update code when you upgrade.

Everything in these guides is implemented and tested, the build enforces 90%
line coverage, and the examples in the guides are compiled by tests. No
production use is known. Maturity is uneven: the core, `lark-app` and
`lark-stream` have been in place longest and are used by the related projects
listed in the README; the actor, cluster and Kafka modules are newer, and
outside their own tests are exercised mainly by `lark-bank` and
[tweet-street](https://github.com/matthewjones372/tweet-street).

Some modules wrap APIs that are themselves experimental:
`lark-actor-remote-kotlinx` uses kotlinx.serialization's ProtoBuf format, which
kotlinx marks experimental, and `lark-structured` uses a JDK preview API and is
not published for that reason.

## Performance

Three JMH suites compare Lark with Pekko or the Kafka client written by hand. Their READMEs give
the method and every number; a few headline results:

- **Streams** ([results](../lark-stream-benchmarks/README.md)): a five-stage
  element-at-a-time chain costs about 91 ns per element on Pekko Streams after
  Lark fuses adjacent stages, against about 150 ns unfused, and about 29 ns on
  Forks. On the actors backend, `mapPar` is about 5.7 times faster than on
  Forks and starting a run about twice as fast, while a plain chain is about a
  quarter slower.
- **Actors** ([results](../lark-actor-benchmarks/README.md)): against Pekko
  Typed, a remote ask round trip takes about 199 µs against 547 µs, and a burst
  of remote tells about 2.1 µs per tell against 4.0 µs. Idle actors take about
  56% of Pekko's heap each.
- **Kafka** ([results](../lark-kafka-benchmarks/README.md)): zio-kafka's
  comparison of 50k records, against the Java client. lark on Forks takes 657 ms
  against 646 ms for the same hand-written loop and 557 ms for one that never
  commits; about 500 ms of each is the consumer's own close.

These numbers were measured on JDK 21 on a shared four-vCPU cloud container,
before the libraries moved to JDK 25, and the READMEs note they are noisy. The
cluster benchmarks against Pekko Cluster are written but have not been run.

## Known limitations

- **JDK 25 is required.** Services on JDK 21 cannot use Lark.
- **No coroutine interop layer.** Lark functions block; call them from
  coroutine code as you would any blocking call.
- **Cancellation is interruption.** Work that never reaches an interruptible
  call cannot be stopped early.
- **Streams:** backends differ in what they run. Pekko-specific operators
  (anything taking a Pekko `Source`, `Flow` or `Sink`) run only on Pekko
  Streams; see [Backends](stream.md#backends).
- **Remoting** is at most once for plain tells, and serialization is yours to
  write or generate. Use reliable delivery for commands that must arrive.
- **The JDBC journal** ships tables for Postgres only, and is tested only on
  Postgres.
- **The compiler plugin** reads a subset of graph shapes and falls back to the
  Gradle task for the rest; see [Applications](app.md#checking-the-graph-in-the-editor).

## The example application

`lark-bank` is a small bank built on the actor, persistence, remoting and
cluster modules. Accounts and transfers are persistent sharded entities. A
transfer is a saga that debits one account and credits another through durable
reliable sends, so a node that dies in the middle delays a transfer without
losing or doubling money. Each node publishes its numbers to a cluster topic,
which the admin page reads.

```bash
./gradlew :lark-bank:run
```

That starts three nodes in one JVM with the journal in memory. Each serves a
page for sending money at <http://localhost:8081> to `8083`, and an admin page
at `/admin` that streams the cluster's numbers and has buttons to generate load
and to crash a node.

- `--args="--jdbc URL"` keeps the journal in a Postgres database instead.
- `--args="--node n2 --jdbc URL"` runs a single node in its own process;
  `--node` requires `--jdbc` so the nodes share a journal.

The HTTP server is the JDK's own `HttpServer`, and the pages are plain HTML and
JavaScript, tested in headless Chromium through Playwright. It is not
published and is not a real bank: there is no authentication, and an account
is chosen by its id.

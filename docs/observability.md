# Observability: logs, metrics and traces

Logging and metrics calls are part of the core `lark` module, so library and
service code can write a log line or count something without taking a logger
or a registry as a dependency. Where the output goes is decided by what is on
the classpath:

| Module | Sends | Adds |
|---|---|---|
| none | log lines to stderr; metrics nowhere | nothing |
| `lark-slf4j` | log lines to SLF4J, annotations in the MDC | `slf4j-api` |
| `lark-micrometer` | metrics to a Micrometer `MeterRegistry` | `micrometer-core` |
| `lark-otel` | OpenTelemetry context across forks, and spans | `opentelemetry-api` |

Each integration registers itself through `ServiceLoader`, so adding the
dependency is the whole setup.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-slf4j:0.10.1")
    implementation("io.github.matthewjones372:lark-micrometer:0.10.1")
    implementation("io.github.matthewjones372:lark-otel:0.10.1")
}
```

The complete examples on this page are compiled by `CoreGuideTest`, apart from
the OpenTelemetry ones.

## Logs

`logDebug`, `logInfo`, `logWarn` and `logError(message, cause)` write to the
`Logger` bound on the current thread. `logAnnotated` binds key-value pairs for
a block, and every line written inside it carries them, including lines
written on forks. `logSpan(name)` adds `<name>_ms`, the time the span had been
running when the line was written.

<!-- observability-logs -->
```kotlin
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logSpan
import io.github.matthewjones372.lark.parMap

fun register(id: String, items: List<Int>): List<Unit> =
    logAnnotated("correlation_id" to id) {
        logSpan("register") {
            parMap(items) { item -> logInfo("checking $item") }   // each line: correlation_id=… register_ms=…
        }
    }
```

An MDC entry does not survive a fork, because it lives in a `ThreadLocal`.
Annotations do, because they are bound in a [`LarkLocal`](core.md#what-a-fork-inherits).

**With `lark-slf4j`,** each line goes to the SLF4J logger named after the
top-level class that wrote it, so `%logger` names that class and a backend can
set levels per package. A line with no class goes to the logger `lark`. Bind
`Slf4jLogger("lark")` to send every line to one logger instead. Annotations
are put in the MDC for the call and restored afterwards, so
`%X{correlation_id}`, a JSON encoder and a log search can read them as fields.
The module compiles against `slf4j-api` 1.7 and works with 2.x.

**Your own logger.** `Logger` is a `fun interface` over `LogLine`, which carries
`level`, `message`, `at`, `cause`, `annotations` and `origin`. Binding one with
`logger.locally(MyLogger()) { … }` takes priority over anything registered on
the classpath, for a whole application or for one block.

## Metrics

Meters are looked up by name when they are used, so nothing is declared up
front and no node takes a registry:

<!-- observability-metrics -->
```kotlin
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.timed

class Adoptions(private val waiting: MutableList<String>) {
    fun adopt(name: String): String = timed("petshop.adopt.duration.seconds") {
        counter("petshop.adoptions").increment()
        gauge("petshop.queue.depth").set(waiting.size.toDouble())
        waiting.remove(name)
        name
    }

    fun adoptTortoise(): Unit = metricTagged("species" to "tortoise") {
        counter("petshop.adoptions").increment()
    }
}
```

- `counter`, `gauge` and `histogram` take a name and optional tags.
- `timed` records the block's duration in **seconds**, whether the block
  returns or throws, and returns what the block returned.
- `metricTagged` binds tags for a block, inherited by forks like log
  annotations. It is separate from `logAnnotated` on purpose: annotations are
  often unique per request, and a metric tag with unbounded values creates one
  time series per request. Keep tag values few and known in advance.
- `readings(name)` reads a metric back from whichever backend is bound, for a
  service that wants to show its own numbers.
- With no backend bound, measurements are recorded nowhere, at no cost.

**With `lark-micrometer`,** measurements go to Micrometer's global registry,
which is where Spring and the OpenTelemetry bridge put theirs. Bind
`MicrometerMetrics(registry)` with `metrics.locally(…) { }` to use another.

### Lark's own metrics

Lark's modules record through the same API, under names starting `lark.`, so
installing `lark-micrometer` exports them with nothing more to write:

- policies, breakers, bulkheads and rate limiters: see [Policies](core.md#policies);
- streams: `lark.stream.elements`, `lark.stream.busy` and
  `lark.stream.waiting` per stage, for a run started with
  [`measured`](stream.md#optimisation-rendering-and-profiling);
- Kafka consumers and producers: `lark.kafka.*`, see
  [Meters](kafka.md#meters);
- actors, remoting, membership, sharding, reliable delivery and topics: see
  [Watching a cluster](cluster.md#watching).

## Traces

`lark-otel` keeps OpenTelemetry's `Context` in a `LarkLocal`, so a span opened
before a `parMap` is the parent of the spans each branch opens. It registers a
`ContextStorageProvider` for the whole JVM, so every library that uses
OpenTelemetry's context, such as an instrumented HTTP client, reads and writes
the same context. That process-wide effect is why it is a separate module.

```kotlin
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.otel.span
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.parMap
import io.opentelemetry.api.trace.Tracer

fun pricing(tracer: Tracer, orders: List<Order>): List<Price> =
    tracer.span("price-all") {                                   // a throw is recorded with ERROR status
        parMap(orders) { order -> tracer.span("price") { price(order) } }
    }

fun registering(tracer: Tracer) =
    tracer.tracedSpan("register") { logInfo("started") }        // the line carries trace_id and span_id
```

`tracedSpan` is `span` that also adds `trace_id` and `span_id` to every log
line written inside it, so a log line names its trace and a trace names its
log lines.

### Across messages, events and records

Log annotations and, with `lark-otel`, the trace context travel further than
forks:

- **Actor messages.** A `tell` or `ask` carries the sender's context to the
  step that handles it, on the same node or, through `lark-actor-remote`, on
  another.
- **Journal events.** Events written to the in-memory or JDBC journal keep the
  context as metadata, available to read models as `Followed.metadata`.
- **Kafka records.** This one is explicit. `lark-kafka` provides
  `ProducerRecord.carrying(Carriers.capture())` to write the current context
  into a record's headers, and `ConsumerRecord.within { }` to restore it while
  the record is handled. Each
  record operator's body also runs with `kafka.topic`, `kafka.partition` and
  `kafka.offset` annotations.

## In tests

`capturingLogs { logs -> … }` and `capturingMetrics { measured -> … }` bind an
in-memory logger or registry for a block, so a test asserts on values rather
than on a backend. See [Testing](testing.md#logs-and-metrics).

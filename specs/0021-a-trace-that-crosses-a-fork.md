# 0021 — A trace that crosses a fork

## Problem

OpenTelemetry's current `Context` is a `ThreadLocal`. A fork here starts a
thread that has bound none of it, so a span opened before a `parMap` is not the
parent of what the branches open: each branch is a root of its own, and a trace
of a fan-out is a handful of unconnected traces.

It is spec 0017's problem again, and 0017 built the answer. `Stream.fromStage`
got a guard, MDC got `logAnnotated`; the trace got nothing, and it is the one a
reader of a distributed system opens first.

## Not doing

- **No SDK.** A service brings its own exporter, sampler and agent. This module
  is the API and where a `Context` lives.
- **No instrumentation.** No HTTP, JDBC or Pekko spans; the agent that already
  writes those benefits from this without knowing about it.
- **No baggage helpers.** Baggage rides in the same `Context` and needs nothing
  here.
- **No metrics.**

## Shape

A module, `lark-otel`, on `lark` and `opentelemetry-api`.

```kotlin
val otelContext: LarkLocal<Context> = larkLocal { Context.root() }

class LarkContextStorage : ContextStorage
class LarkContextStorageProvider : ContextStorageProvider
```

Registered through `META-INF/services`, so nothing in a service's own code
changes:

```kotlin
tracer.span("register") {
    parMap(orders) { order -> tracer.span("price") { price(order) } }   // children, not roots
}
```

`tracedSpan` is `span` with the ids on the log lines written inside it:

```kotlin
tracer.tracedSpan("register") { logInfo("started") }   // trace_id=… span_id=…
```

`LarkLocal` grows the pair an attach-and-detach API needs:

```kotlin
fun attach(value: A): Detach
```

## Why this shape

The `ContextStorage` SPI rather than a lark-shaped span API. A service's spans
mostly are not written by its own code — they come from an agent, a client
library, a framework — and only the storage reaches those. A `span { }` of our
own would fix the spans we wrote and leave every other one rooted at the fork.

The cost is that the SPI is process-wide: with this module on the classpath,
every library using OpenTelemetry's context in the JVM reads and writes it
here. That is the point, and it is why this is a module a service opts into
rather than anything in `lark`.

`attach` on `LarkLocal` is the smallest thing that makes the SPI implementable:
`locally` takes a block and `ContextStorage.attach` hands back a `Scope` to be
closed later.

## Stack

- [x] **`spec-0021-context`** — `LarkLocal.attach`, `LarkContextStorage`, the
      service registration, `span`, `tracedSpan`, and the dependency test.
      Done when: a span opened before a `parMap` is the parent of what each
      branch opens, and the storage OpenTelemetry finds is this one.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is the SPI the right default, or should a service call
   `ContextStorage.addWrapper` itself?** Recommend the SPI: a wrapper has to be
   installed before anything reads the context, which is a startup-ordering
   problem nobody wants to debug twice.
2. **Does `opentelemetry-sdk-testing` belong in the test classpath?** No, and
   the build says why: it registers a `ContextStorageProvider` of its own, wins
   the ServiceLoader race, and leaves this module's storage untested. The
   exporter it would have supplied is a dozen lines.

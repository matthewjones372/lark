# 0034 — A number worth keeping

## Problem

`logInfo` writes to whichever `Logger` the thread has bound, and
[0032](0032-a-logger-someone-else-already-configured.md) made the backend a
dependency rather than a line in `main`. There is nothing of the kind for a
number. A service that wants to count adoptions reaches past `lark` for a
`MeterRegistry`, threads it through the graph as a node, and takes a dependency
on Micrometer in its domain to do it.

No node takes a logger. No node should have to take a registry either.

ZIO's answer is a `Metric` as a value applied with `@@`, and connectors supplied
as layers. The half worth copying is that the description is a value and what
becomes of it is somebody else's dependency.

## Not doing

- **No aggregation.** No percentiles, no sliding windows, no bucket maths.
  Every backend this targets has spent years on that.
- **No summary or frequency** in a first pass, and no exporter or scrape
  endpoint: a service already has one.
- **No dependency in `lark`.** JDK and Arrow, as ever.

## Shape

Nothing to declare, nothing to wire:

```kotlin
counter("petshop.adoptions").increment()
gauge("petshop.queue.depth").set(queue.size.toDouble())
timed("petshop.adopt") { shop.adopt(id, by) }

metricTagged("species" to "tortoise") {   // scoped, and a fork inherits it
    counter("petshop.adoptions").increment()
}
```

Setup is a dependency and nothing else, as it is for logging:

```kotlin
implementation("io.github.matthewjones372:lark-micrometer:$larkVersion")
```

- `Metrics` is the seam, bound in a `LarkLocal` and found on the classpath
  through a `ServiceLoader` — the shape `Logger` has after 0032.
- The default records nothing and fails at nothing. `capturingMetrics { }` binds
  one a test can read, as `capturingLogs` does.
- One adapter, many backends: a `MeterRegistry` already reaches Prometheus,
  Datadog, StatsD and OTLP.

## Why this shape

**A metric is not a log line, and the seam is where that shows.** A `Logger`
takes a `LogLine` per event, because every line is wanted. A `Metrics` taking a
measurement per increment would hand a backend a million values a second to add
up, when holding one number is the backend's whole job. So `Metrics` answers
with instruments, and looking one up by name is a map hit the backend already
does — which is what lets a call site say `counter("…").increment()` with
nothing held in a `val` and nothing cached here.

**Log annotations are not metric tags.** `logAnnotated("correlation_id" to id)`
is exactly the propagation a tag wants, and reusing it would be a cardinality
bomb: one series per request, and a dead Prometheus by Thursday. `metricTagged`
is a second thing with a second scope on purpose, and the rule — few values,
known in advance — belongs in its KDoc.

The alternative is tags at every call site and no scope. Safer, and it loses the
reason to put this in `lark` at all: a tag bound once is on every measurement a
fork takes underneath it, which a `ThreadLocal` cannot do.

## Stack

- [ ] **`spec-0034-the-instruments`** — `Counter`, `Gauge`, `Histogram`, the
      `Metrics` seam, the recording-nothing default, `ServiceLoader` discovery,
      `capturingMetrics`.
      Done when: a counter incremented three times reads as 3 in a capture,
      nothing bound records nothing and throws nothing, and
      `NoOtherDependenciesTest` in `lark` is unchanged.
- [ ] **`spec-0034-tags-and-timing`** — `metricTagged`, inherited by a fork, and
      `timed`.
      Done when: a measurement on a `parMap` fork carries the tag its opener
      bound, one outside the block does not, and `timed` records a duration and
      returns the block's value.
- [ ] **`spec-0034-micrometer`** — `lark-micrometer`: the adapter, the service
      file, and a `NoOtherDependenciesTest` naming `micrometer-core` and no more.
      Done when: with the module on the classpath and nothing bound, a counter
      appears in a `SimpleMeterRegistry` with its tags.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where does the `MeterRegistry` come from?** Recommend the adapter take one
   and fall back to Micrometer's own global, since a service on Spring has one
   there already — setup stays a dependency and nothing else.
2. **Is a gauge pushed or pulled?** Micrometer's are pulled. Recommend the
   adapter hold a number per gauge and register a pull over it, so the caller
   gets `set` and the backend gets what it wants.
3. **What does `timed` record?** Recommend milliseconds into a histogram, and a
   `Timer` only if the distinction turns out to matter to a backend.
4. **Should an unbounded tag be refused?** It cannot be known from one call.
   Recommend documenting the rule and not policing it.

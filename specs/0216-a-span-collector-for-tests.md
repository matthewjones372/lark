# 0216 — A span collector for tests

## Problem

A test that checks the spans a service makes needs a tracer that keeps the spans it ends. surefold ships no such
tracer, so each test writes one: an `SdkTracerProvider` with a `SimpleSpanProcessor` around a `SpanExporter` that
adds to a list. surefold-otel's own tests keep one (`Collected`, with a `Tracing` and a `Traced` around it), and
petshop keeps another (`Ended`, petshop#125).

OpenTelemetry's ready-made one, `InMemorySpanExporter` in `opentelemetry-sdk-testing`, can't be used beside
surefold-otel. That artifact registers a `ContextStorageProvider`, `SettableContextStorageProvider`, and surefold-otel
registers `SurefoldContextStorageProvider`. With two on the classpath, OpenTelemetry's `LazyStorage` logs "Found
multiple ContextStorageProvider" and falls back to its default storage, so a test that checks a trace crosses a fork
runs on storage the service doesn't use.

## Not doing

- **No span matchers.** A test checks a span's kind, name and attributes with `shouldBe` on its fields.
- **No waiting in `named`.** A test that waits for a span to end says so with `eventually`.
- **No surefold-junit `Resource`.** Each test opens its own collector, so it reads only its own spans.
- **No change to surefold-otel's main code.**

## Shape

```kotlin
Spans().use { spans ->
    relay(every, outbox, bus, spans.tracer).start(TestStreams(clock))
    clock.adjust(every)
    spans.named("publish").map { it.kind } shouldBe listOf(SpanKind.PRODUCER)
}
```

- **`surefold-otel-test`**, a new published module, depends on `surefold-otel` and `opentelemetry-sdk`, and not on
  `opentelemetry-sdk-testing`.
- **`Spans`** is a `tracer` whose ended spans it keeps. `all()` gives them in the order they ended, `named(name)` those
  of one name, and `close()` shuts the tracer provider down.
- **surefold-otel's tests** use it, and `Collected` goes.
- **Docs:** `docs/observability.md` "In tests", `docs/modules.md`, `llms.txt` and the surefold skill name the module.

## Why this shape

A test module beside the one it tests is how surefold already ships `surefold-kafka-test` and `surefold-stream-test`.
It keeps the SDK and a test class off a service's runtime classpath, which surefold-otel promises: the API alone.

**Alternative:** `Spans` in surefold-otel itself. One fewer module, but every traced service would carry
`opentelemetry-sdk` at runtime. Recommended: the module.

## Stack

- [x] **`spec-0216-otel-test`** — `surefold-otel-test` with `Spans`, its tests, surefold-otel's tests on it, and the
      docs.
      Done when: `:surefold-otel-test:check` and `:surefold-otel:check` pass, and a test shows OpenTelemetry's storage is
      still surefold's with the module on the classpath.

## Acceptance

```bash
./gradlew :surefold-otel-test:check :surefold-otel:check
```

## Open questions

None. The owner took petshop#125's recommendations (2026-10-26), which fix this shape.

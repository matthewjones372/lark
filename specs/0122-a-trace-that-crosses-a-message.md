# 0122 — A trace that crosses a message

## Problem

Spec 0021 carries OpenTelemetry's context across a fork. A message is the other way work moves here, and it carries
nothing: an actor handles a `tell` or an `ask` in the context its own thread happens to hold, so a span opened by the
sender is not the parent of what the receiver does. A request that becomes an entity's command, a saga's step and a
leg on another node is one trace per actor, with nothing to join them. The annotations `logAnnotated` binds are lost
the same way, so the lines a receiver writes do not carry the sender's `trace_id` (lark-bank spec 0024).

## Not doing

- **No new span per message.** Carrying the context is this spec; a span around a handler is the service's, or
  `tracedSpan`'s, because a span per message at thousands a second is the tracer's load.
- **No dependency on OpenTelemetry in `lark-actor`.** What rides a message is opaque to it.
- **No baggage policy.** Whatever the context holds rides along; trimming it is the service's.

## Shape

`lark-actor` carries a `Carried` value with each message: captured from the sender's locals when it is sent, and
attached around the receiver's handling of it. What is captured is registered, as the logger is, by a module on the
classpath:

```kotlin
interface Carrier {
    fun capture(): Map<String, String>        // at tell/ask, on the sender
    fun <A> within(carried: Map<String, String>, block: () -> A): A   // around the handler
}
```

`lark-otel` registers one that writes `traceparent` and `tracestate` with W3C's propagator; `lark` itself registers
one for `logAnnotated`'s annotations. With neither on the classpath, nothing is captured and a message costs what it
does today.

Across nodes, `lark-actor-remote` puts the map in the envelope, and each wire format (`-kotlinx`, `-protobuf`, `-avro`)
gains an optional field for it, absent when empty, so a node without this reads one with it.

## Why this shape

A map of strings, not the context itself: it crosses the wire as it is, and it is what every propagator already reads
and writes. Captured at send rather than at enqueue so a message forwarded by a router keeps its first sender's
context. The alternative, a wrapper message type a service sends explicitly, leaves every library's messages — the
sharding coordinator's, a stream's — outside the trace.

## Stack

- [ ] **`spec-0122-local`** — `Carrier`, its ServiceLoader, capture on `tell` and `ask`, `within` around handling;
      `lark-otel`'s and the annotations' carriers. Done when: a span open at an `ask` is the parent of a span the
      receiving actor opens, and its line carries the sender's annotation.
- [ ] **`spec-0122-remote`** — the envelope field in each wire format. Done when: the same holds for an actor on
      another node, and a node built before this reads a message from one built after.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Persistent entities**: is the command's context the one their events are written in (spec 0123 reads it), or
   the entity's own? Recommend the command's.
2. **Timers** an actor sets for itself: the context of the message that set them, or none? Recommend none: a timer's
   handling is the actor's own work, and a trace that lasts as long as a retry loop is not a request's.
3. **Cost**: one map per message when a carrier is registered. Recommend measuring with `lark-actor-benchmarks` in
   `spec-0122-local`, and skipping the capture when the context is empty.

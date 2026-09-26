# 0071 — Messages in Protobuf or Avro

## Problem

0068 makes a message's wire form the service's own: a `MessageCodec` writes it
field by field. A service that already describes its messages in Protobuf or
Avro, often because other systems read them too, writes that codec by hand
today: the library's bytes through `WireOut.bytes`, a tag per message type, and
the reply of an `ask` written beside them, since no generated class can hold a
lark `Reply` or `ActorRef`. Every service does it the same way, and gets the
tag table or the reply order wrong in its own way.

## Not doing

- **A JSON codec.** Kotlin's serialization is a plugin a service chooses; a
  hand-written codec is short enough.
- **A schema registry client.** Avro here resolves schemas from a store the
  service gives it; Confluent's registry is one store it can give.
- **Protobuf or Avro in `lark-actor-remote`.** It keeps no dependencies; each
  format is a module of its own.
- **The cluster's own messages.** Gossip stays in its internal codec.

## Shape

```kotlin
// lark-actor-remote-protobuf
val placed: MessageCodec<OrderPlaced> = Protobuf.codec(OrderPlaced.parser())

// A protocol of several generated messages, each under a tag that never changes.
val orders: MessageCodec<Message> = Protobuf.oneOf {
    message(1, OrderPlaced.parser())
    message(2, OrderCancelled.parser())
}

// An ask: the generated request with the lark reply beside it.
data class Quote(val request: QuoteRequest, val reply: Reply<Price>)
val quote: MessageCodec<Quote> =
    Protobuf.asked(QuoteRequest.parser(), Protobuf.codec(Price.parser()), ::Quote, Quote::request, Quote::reply)

// lark-actor-remote-avro
val placed: MessageCodec<OrderPlaced> = Avro.codec(OrderPlaced::class.java, schemas)
```

- **Protobuf** writes a message's own bytes. `oneOf` writes the tag first and
  refuses a tag used twice or a type it has no tag for, when it is built.
- **Avro** writes single-object encoding: the writer schema's fingerprint, then
  the record. The reader resolves an older or newer writer schema from
  `schemas`, a `SchemaStore` the service gives it, so two nodes on different
  versions of a record still understand each other.
- **`asked`**, in both, writes the reply as 0068 does and the request in the
  format beside it, so an `ask` crosses nodes with a generated message in it.

## Why this shape

Each format keeps its own evolution rules: Protobuf by field numbers, Avro by
schema resolution. Wrapping both in one lark envelope would add a second set of
rules on top. The alternative is one generic "bytes codec" in the core that
each service adapts; it saves two modules and leaves every service writing the
same tag table and reply order. Recommended: a module per format.

## Stack

- [ ] **`spec-0071-protobuf`** — `lark-actor-remote-protobuf`: `codec`,
      `oneOf` and `asked`. Done when: a generated message, a `oneOf` protocol
      and an ask cross between two nodes, and a duplicate tag fails when built.
- [ ] **`spec-0071-avro`** — `lark-actor-remote-avro`: `codec` with a
      `SchemaStore`, and `asked`. Done when: a node writing version 2 of a
      record and one reading version 1 understand each other.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Full Protobuf, or `protobuf-javalite` too?** Recommended: full only; lite
  users can pass their parser the same way, which a later entry can confirm.
- **Avro: specific records only, or generic records as well?** Recommended:
  specific first; generic needs the schema at the call site anyway.
- **`oneOf` over any `Message`, or over a sealed Kotlin type the service
  maps?** Recommended: any `Message`, since generated classes cannot share a
  sealed parent.

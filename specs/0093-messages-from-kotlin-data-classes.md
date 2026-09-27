# 0093 — Messages from Kotlin data classes

## Problem

0071 gives a service whose messages are described in `.proto` a codec it does
not write. The price is that the `.proto` is the source: `protoc` generates
Java classes, and the service maps them to and from its own Kotlin data classes
by hand. lark-bank's mapping is 170 lines for 148 lines of `.proto`, and every
new field is written three times. 0071 left Kotlin's serialization plugin to
each service. Such a service now writes the same tag table and reply order that
0071 was written to spare it.

## Not doing

- **No change to 0071.** `Protobuf` and `Avro` stay for services whose schema
  is owned elsewhere.
- **No JSON.** Only binary formats: ProtoBuf by default, and any kotlinx
  `BinaryFormat` a service passes, such as CBOR.
- **No versioning.** 0091's `versioned` wraps these codecs like any other.
- **No Gradle plugin.** The `.proto` is text a function returns; the service
  decides where it is written.

## Shape

```kotlin
@Serializable
sealed interface AccountEvent {
    @Serializable data class Deposited(@ProtoNumber(1) val amount: Money, @ProtoNumber(2) val reference: String) : AccountEvent
    @Serializable data class Withdrawn(@ProtoNumber(1) val amount: Money, @ProtoNumber(2) val reference: String) : AccountEvent
}

// Several classes, each written after a tag that never changes: the same rule as Protobuf.oneOf.
val events: Kotlinx.OneOf<AccountEvent> = Kotlinx.oneOf {
    message<AccountEvent.Deposited>(1)
    message<AccountEvent.Withdrawn>(2)
}
persistent(id, empty, codec = events.events(), …)            // an EventCodec<AccountEvent>
Cluster.sharding(kind, events.messages(), …)                  // a MessageCodec<AccountEvent>

val one: MessageCodec<Money> = Kotlinx.codec(Money.serializer())
val snapshots: StateCodec<AccountState> = Kotlinx.state(AccountState.serializer())
val ask = Kotlinx.asked(Open.serializer(), answers, ::OpenAsk, OpenAsk::request, OpenAsk::reply)

// The .proto the classes make, for a reader in another language.
val proto: String = Kotlinx.proto(events, package = "bank.v1")
```

- **A new module, `lark-actor-remote-kotlinx`**, depending on `lark-actor-remote`
  and `kotlinx-serialization-protobuf`, and nothing else.
- **`codec`, `state` and `events`** write one class's own bytes in the format.
- **`oneOf`** writes the tag and then the class's bytes. Like `Protobuf.oneOf`,
  it refuses a tag or a class given twice when it is built, and a class it has no
  tag for when it writes. From one table it gives a `MessageCodec`, an
  `EventCodec` and a `StateCodec`. It writes the tag as a protobuf key, so a
  value is exactly a message with a `oneof` whose field numbers are the tags.
- **`asked`** writes the reply as 0068 does, and the request in the format
  beside it, as 0071's `asked` does.
- **`proto`** is kotlinx's `ProtoBufSchemaGenerator` over the classes in a
  table. It adds one comment line per tag, so the framing is written down next
  to the messages it frames.

## Why this shape

The data class is the only definition: the fields, their numbers and their
types are written once, where the code uses them. The tag table stays lark's,
as in 0071, and not kotlinx's own polymorphism. kotlinx writes a class's name
on the wire, so a rename breaks every stored event. Its `@ProtoOneOf` needs a
wrapper class per case, which a domain type should not carry. A number that
never changes is the rule every lark codec already follows.

## Stack

- [x] **`spec-0093-codecs`**: the module, `codec`, `state`, `oneOf` (message
      and event codecs) and `asked`, with `NoOtherDependenciesTest`.
      Done when: a sealed hierarchy of data classes, value classes and enums
      crosses two nodes and survives a journal replay, and a tag given twice
      fails when the table is built.
- [x] **`spec-0093-proto`**: `proto()`, and a reference test that `protoc`
      parses what it writes.
      Done when: the `.proto` for the test hierarchy is pinned as a golden file,
      and bytes written by the codec parse with the classes `protoc` generates
      from it.

## Acceptance

```bash
./gradlew :lark-actor-remote-kotlinx:check
```

## Open questions

Answered 2026-09-27, taking each recommendation:

1. **Lark tags, or kotlinx's own polymorphism?** Lark tags. A class's name
   never reaches the wire, and a domain class needs no wrapper class.
2. **One module, or the journal codecs in a second?** One module.
3. **Pin kotlinx's version, or take the service's?** It is declared as `api`
   at the version this module is tested against, and the docs say its protobuf
   support is experimental.

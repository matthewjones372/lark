# 0254 — Bytes kept are bytes nobody can change

## Problem

Spec 0249 gave Surefold `ByteChunk`, but the classes that keep bytes still keep a `ByteArray` in a public `val`,
and make up for it by hand:

- **`StoredResponse.body`** (`surefold-idempotency`) writes `equals`, `hashCode` and `toString` by hand to compare a
  body by contents, which is what `ByteChunk` already does. `Reservation.Replay` relies on that equality.
- **`StoredEvent.bytes`**, **`Snapshot.bytes`** and **`FeedEvent.bytes`** (`surefold-actor`) are read by recovery
  and by every projection. The in-memory journal and snapshot store guard against that by hand, with a `copyOf()`
  on every write and every read (five places in `Journal.kt` and `Snapshots.kt`). A journal someone else writes
  has to remember to do the same.
- **`UnreadMessage.bytes`** (`surefold-actor-remote`) is a dead letter's payload, read by whoever watches dead
  letters.

## Not doing

- **Codecs.** `EventCodec`, `StateCodec` and `MessageCodec` keep `encode(): ByteArray` and
  `decode(ByteArray)`. Users implement them, and every serializer they would call answers a `ByteArray`.
- **Crypto.** Key material and sealed bytes in `surefold-tenancy-crypto` and `surefold-auth` stay `ByteArray`. A
  key should be zeroable after use, and a `ByteChunk` cannot be.
- **Kafka.** Its record types belong to `kafka-clients`.
- **`surefold-files`.** Streaming a file through `Stream.read` is a spec of its own.
- **`Wire.kt`.** Its frames move to 0249's `framed` and `unframed` in that spec's last entry.

## Shape

```kotlin
// surefold-idempotency: a data class now, with no hand-written equality.
data class StoredResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteChunk)

// surefold-actor: what a journal or snapshot store answers.
class StoredEvent(val sequence: Long, val bytes: ByteChunk, val metadata: Map<String, String> = emptyMap())
class Snapshot(val sequence: Long, val bytes: ByteChunk)
class FeedEvent(val offset: Long, val id: PersistenceId, val sequence: Long, val bytes: ByteChunk, ...)

// surefold-actor-remote
class UnreadMessage(val bytes: ByteChunk)

// The write side, Journal.append and SnapshotStore.save, keeps taking ByteArray (see Open questions).
```

Recovery hands `codec.decode(event.bytes.toByteArray())` what it hands today.

## Why this shape

Only values that are *kept and handed out* change. That's where a shared array has to be guarded by copies, and
where equality by contents matters. A codec's bytes pass straight through to a serializer, so a `ByteChunk` there
would only add a copy.

**Cost.** The in-memory stores stop copying on read, since a chunk can be shared, and copy once for `decode`
instead: no change. The JDBC journal copies twice more per event read, into the `ByteChunk` and out again for
`decode`. Events are small, but recovery reads many, so the journal entry measures this.

**Alternative:** a public `ByteChunk.wrap(array)` that skips the copy. Recommended against for now: it hands back
the one thing `ByteChunk` exists to take away, a reference that can change it.

## Stack

- [ ] **`spec-0254-idempotency`**: `StoredResponse.body` is a `ByteChunk`, and `StoredResponse` is a data class.
      Done when: two responses with equal bodies are equal and hash the same, with no `equals` or `hashCode`
      written in `Idempotency.kt`; a stored response reads back equal from Postgres.
- [ ] **`spec-0254-journal`**: `StoredEvent`, `Snapshot` and `FeedEvent` carry a `ByteChunk`, in the in-memory,
      JDBC and sharded stores.
      Done when: no `copyOf()` is left in `Journal.kt` or `Snapshots.kt`; a recovery from the JDBC journal in
      `surefold-actor-benchmarks` is within 5% of `main`'s.
- [ ] **`spec-0254-unread`**: `UnreadMessage.bytes` is a `ByteChunk`.
      Done when: a dead letter for an unknown path carries the frame's payload, equal by contents.

## Acceptance

```bash
./gradlew :surefold-idempotency:build :surefold-actor:build :surefold-actor-journal-jdbc:build \
  :surefold-actor-remote:build
```

## Open questions

1. **Should `Journal.append` and `SnapshotStore.save` take `ByteChunk` too?** Recommended: no. They're the write
   side of an SPI users implement, the bytes come straight from `encode`, and nothing else holds them.
2. **Is this one breaking release, or a deprecation cycle?** Recommended: one release. These are value classes
   with no subtypes to keep working, and the change is a type in a constructor.
3. **What if the recovery benchmark is more than 5% slower?** Recommended: stop at the journal entry, and write a
   spec for a `decode(ByteChunk)` on `EventCodec` with a default that copies.

# 0091 — Events that change shape

## Problem

A persistent entity's events outlive the code that wrote them. Since 0063 an
event is bytes through the service's own `EventCodec`, and a snapshot's state
through its `StateCodec` (0074). When the event's class changes, the codec
must still read what was written years ago:
- a field is added;
- one is renamed;
- one event is split in two;
- one is dropped.

Protobuf and Avro (0071) handle adding and removing fields, but not a rename
of meaning, a split, or an event that should now be read as another. Today a
service writes that logic into its codec's `decode` by hand, with no
convention for which version a byte array is. Every service invents its own
header, and a mistake is found only when an old entity is replayed in
production.

## Not doing

- **Rewriting the journal.** Old events stay as written; they are read through
  the upgrade each time. A migration that rewrites a table is the service's.
- **Schema registries.** The version is a number in the bytes, and the
  upgrades are code the service writes.
- **Downgrades.** An older version of the service cannot read events a newer
  one wrote; a rolling deploy writes the new version only once every node
  reads it.

## Shape

```kotlin
val orderEvents = versioned(
    current = 3,
    codec = OrderEventV3Codec,                        // writes and reads version 3
    upgrades = mapOf(
        1 to Upgrade { bytes -> listOf(OrderEventV1Codec.decode(bytes).toV2()) },
        2 to Upgrade { bytes -> OrderEventV2Codec.decode(bytes).toV3() },   // one event may become several
    ),
)
persistent(id, empty, codec = orderEvents, command = …, event = …)
```

- **A version prefix.** `versioned` writes a short marker and the version
  before the codec's bytes. Bytes with no marker are version 1, so a journal
  written before this reads unchanged.
- **Upgrades chain.** An event of version `n` goes through `upgrades[n]`,
  `upgrades[n+1]` and so on up to `current`. A version with no path is an
  error at the event's replay that names the id, the sequence number and the
  version, not a crash inside the service's codec.
- **One event may become several.** An upgrade answers a list, so an event
  split in two replays as two, in order, while keeping the sequence number of
  the event it came from.
- **Snapshots too.** `versionedState` does the same for a `StateCodec`, with
  a single state per upgrade.
- **Checked at start.** `upgrades` that skip a version, or a `current` below a
  key, fail when the codec is built, not when an old event is read.

## Why this shape

A version in the bytes and upgrades in code is what Pekko's event adapters and
most event-sourcing libraries converge on. It keeps the journal immutable and
puts the knowledge of old shapes in one place per event type, tested like any
other code. The alternative is upcasting in the service's own `decode`, which
works but has no convention: nothing checks the chain is complete, and nothing
names which version failed. Recommended: `versioned` as a wrapper codec, so
the journal and `persistent` do not change.

## Stack

- [x] **`spec-0091-versioned`** — `versioned`, `Upgrade`, the prefix and the
      chain, in `lark-actor`. Done when: events written as versions 1 (with no
      prefix), 2 and 3 replay as version 3, one version-1 event replays as two,
      and a missing upgrade is refused when the codec is built.
      ([#257](https://github.com/matthewjones372/lark/pull/257))
- [ ] **`spec-0091-state`** — `versionedState` for snapshots, and a test that
      an entity recovers from an old snapshot and newer events. Done when:
      that recovery reaches the same state as a full replay.
- [ ] **`spec-0091-guide`** — a section in `docs/actors.md` (spec 0089) on
      changing an event, compiled with the rest.

## Acceptance

```bash
./gradlew :lark-actor:build
```

## Open questions

- **A wrapper codec, or versioning inside `Journal`?** Recommended: a wrapper,
  as above; the journal stays bytes.
- **Is unmarked data version 1?** Recommended: yes, so every journal written
  before this reads unchanged.
- **Can one event upgrade to several?** Recommended: yes, as a list; a split
  event is the common case a field rename cannot express.
- **What does a projection (0075) see?** Recommended: the upgraded events,
  through the same codec, so a read model never sees an old shape.

Decided (2026-09-27): every open question goes as recommended. Versioning is
a wrapper codec, and the journal stays bytes; unmarked data is version 1; an
upgrade may turn one event into several; and a projection sees the upgraded
events.

Decided while building `spec-0091-versioned`:
- **What an upgrade returns.** An `Upgrade` turns the bytes of version n into
  the bytes of one or more events of version n+1, not into decoded objects as
  the sketch showed. That is what makes the chain real: version 4 adds one
  upgrade, from 3, and the upgrades from 1 and 2 stay as they are.
- **How several events are read.** `EventCodec` gains `decodeAll`, which
  defaults to one `decode`, so every codec written before this is unchanged.
  Replay, `Journal.events` and a projection read through it, and `decode`
  refuses an event that upgrades to several.
- **The mark.** It is `\0lark:v` and a four-byte version. Bytes without it are
  version 1.
- **A projection and a split event.** Each part but the last carries the offset
  before the event's own. A run stopped between the parts reads the event again
  from its first part, which is the at-least-once a projection already
  promises.
- **What a replay says.** One that meets a version the codec cannot read names
  the entity, the event's sequence number and the version.

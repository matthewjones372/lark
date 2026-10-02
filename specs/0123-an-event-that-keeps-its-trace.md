# 0123 — An event that keeps its trace

## Problem

An event written to the journal is read later, by a projection or by the Kafka publisher, on another thread and
often another node, and by then the request that caused it is over. Nothing in the row says which request that was,
so the trace (spec 0122's, carried into the entity) ends at the append, and whoever reads the event downstream —
another service, through Kafka — starts a trace of its own (lark-bank spec 0024).

## Not doing

- **No span for reading the journal.** A projection's or publisher's batch is its own work; the event links back.
- **No change to an event's payload or its schema.** The trace is the row's metadata, beside the event.
- **No backfill.** Events written before this have none.

## Shape

The journal row gains `metadata`, a nullable `jsonb` of string pairs, written from spec 0122's `Carrier.capture()`
at the append — so a command handled inside a trace writes events that carry it — and read back with each event:

```kotlin
data class Persisted<E>(val event: E, /* … */ val metadata: Map<String, String>)
```

`lark-kafka`'s producer puts each pair on the record as a header. Its consumer takes them off and handles the record
`within` them when it handles one record at a time; a batch handler gets each record's map, to link to.

A Liquibase changeset adds the column; `lark-actor-journal-jdbc`'s readers tolerate its absence until it has run.

## Why this shape

Metadata on the row rather than inside the event: an event is a domain fact and its schema a contract (lark-bank spec
0015), and which request wrote it is neither. A map rather than a `traceparent` column, so the annotations ride with
it and the next thing someone wants carried needs no migration. Headers on Kafka because that is where every consumer
library looks for them.

## Stack

- [x] **`spec-0123-journal`** — the column, its changeset, writing at append, `metadata` on what is read back.
      Done when: an event persisted inside a span reads back with its `traceparent`, and one outside with none.
      Done: `JournalMetadataTest`, with `logAnnotated`'s carrier, the one `lark` registers itself: an append inside it
      reads back, and is fed, with its annotations, alone and committed as a group; one outside, with none. The
      journal captures at `append`, on the thread the command is handled on, so `Journal`'s signature is unchanged;
      `StoredEvent` and `FeedEvent` gain `metadata`, empty by default. The slice mover copies it. Null in the column
      when nothing was carried.
- [ ] **`spec-0123-kafka`** — headers out and in, `within` a single record's handling. Done when: a record published
      from an event written in a trace is handled, by a consumer, in that trace.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Child or link** for a single record's handling? Recommend child: it is what makes a request's trace whole,
   which is what lark-bank wants; OpenTelemetry's messaging conventions allow either.
2. **`jsonb`, or `bytea`** as the payload is? Recommend `jsonb`: it is small, and readable at `psql` during an incident.

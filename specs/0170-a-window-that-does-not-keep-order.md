# 0170 — A window that does not keep order

## Problem

`mapPar` and `mapAsync` answer in the input's order, so one slow call holds back every call behind it, even when
they finished long ago. Reactor's `flatMap` and Pekko's `mapAsyncUnordered` pass each answer on as it completes.
Where order does not matter (independent writes, fan-out to a sink, a Kafka topic with no key), a Lark user today
either pays for head-of-line blocking or falls back to `mapParKeyed` with a unique key, which tracks a tail per key
for no reason.

In `blocking-vs-reactive`, Reactor's contestant uses unordered `flatMap` and Lark's uses ordered `mapPar`. Until
Lark has an unordered operator, that comparison is not like for like.

## Not doing

- No change to `mapPar`, `mapAsync` or `mapParKeyed`.
- No unordered `flatMapMerge`: that already interleaves.
- No Pekko-only variant: these run on every backend.

## Shape

Pekko's name, on `Stream` and `Pipe`, on every backend:

```kotlin
val written: Stream<WriteFailed, Receipt> =
    records.mapParUnordered(parallelism = 64) { record -> store.write(record) ?: raise(WriteFailed(record.id)) }

val fetched: Stream<Nothing, Body> =
    urls.mapAsyncUnordered(parallelism = 1_000) { url -> client.sendAsync(url).thenApply { it.body() } }
```

- Answers in completion order. At most `parallelism` in flight, so backpressure is unchanged.
- A failure or a declared error ends the run as it does in the ordered operators, interrupting or cancelling the
  rest.
- On Forks, built on 0169's `Handoff`: each completion puts its answer straight into the ring, with no slots and no
  head to wait on. On Pekko, `mapAsyncUnordered`. On Actors and TestStreams, the ordered implementation is allowed
  (any order is a valid unordered order), as `mapParKeyed` already does.

## Why this shape

Separate operators with Pekko's names, rather than an `ordered = false` flag on the existing ones. A flag doubles
every overload of `mapPar` and hides a semantic difference behind a boolean. The names already exist in Pekko,
which `lark-stream-pekko` users know. Recommended: separate operators.

## Stack

- [ ] **`unordered-description`** — the two operators and their `Node`s in `lark-stream`, with Pekko,
      Actors and TestStreams compiling them.
      Done when: a parity test asserts the multiset of answers matches the ordered operator's on every backend.
- [ ] **`unordered-on-forks`** — Forks runs them on `Handoff`.
      Done when: a Forks test shows a slow first element does not delay the others, and parity is green.
- [ ] **`unordered-docs`** — `docs/stream.md` operator table rows, and a compiled example.
      Done when: `ReadmeExampleTest` compiles the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

- **Does this wait for 0169?** Recommended: yes. Without `Handoff`, the Forks version would be a third copy of
  today's window.
- **Should `mapParKeyed` with a unique key be documented as the old workaround?** Recommended: no. Point to the new
  operator instead.

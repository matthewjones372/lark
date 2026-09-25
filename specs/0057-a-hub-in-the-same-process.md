# 0057 — A hub in the same process

## Problem

A service that wants one stream's elements read by several others in the same
process has no backend-neutral way to do it. Pekko's `BroadcastHub` needs a
materializer, so it only runs on Pekko. Petshop moved to Forks and had to write
its own bus instead: about 80 lines of queues, a lock and a marker object.

That bus has a leak lark would have to avoid too. A subscriber whose run stops
is never removed. Its queue fills, and after that every publish is refused as
"full", even though nobody is reading that queue.

## Not doing

- **No persistence, no other process.** Something with a broker behind it is
  Kafka's job (0056).
- **No replay for late subscribers.** A new subscriber reads from the moment it
  subscribes. The one exception is below: what was published before anyone
  subscribed.
- **No many-to-one hub.** Several producers into one stream is already
  `merge`.
- **No Pekko `BroadcastHub` compile.** The hub is the same code on every
  backend.

## Shape

```kotlin
val hub = Hub<ShopEvent>(capacity = 256)

hub.publish(event)            // Either<HubRefused, ShopEvent>: Full or Closed, never a silent drop
events.publishTo(hub)         // Stream<Nothing, A> in, Stream<HubRefused, A> out: each published in turn

val tally: Stream<Nothing, ShopEvent> = hub.subscribe()   // a description; each run is a subscriber
tally.runFold(Tally()) { t, e -> t + e }.start(Forks())

hub.close()                   // subscriptions end Done once they've read what they were sent
```

- `Hub<A>(capacity)` in `lark-stream`. Each subscriber gets its own bounded
  queue of `capacity`.
- `publish` is all-or-nothing, under one lock. If any subscriber's queue is
  full, the element is refused for everyone, and nobody gets a copy.
- Before the first subscriber arrives, up to `capacity` elements are held, and
  the first subscriber gets them in order. After that, a hub with no
  subscribers accepts and drops, as a broadcast to nobody does.
- `subscribe()` is `Stream.blocking` (0055):
  - `open` registers the subscriber's queue when a run starts, not when
    `subscribe()` is called, so a description can be run twice;
  - `next` is the queue's `take`;
  - `wake` puts a marker in the queue;
  - `close` unregisters the queue, and that is what fixes the leak above.
- `publish` never blocks. A publisher that wants to wait for room can use
  `mapPar` with a retry schedule. That is the publisher's decision; the hub
  doesn't wait.

## Why this shape

Refusing all-or-nothing is what an at-least-once publisher needs, as petshop's
outbox relay shows: a refused element is still in the outbox and is published
again on a later tick. There are two alternatives.
- Drop the element only for the subscribers that are full. The publisher never
  sees it, and a slow reader silently misses elements.
- Block the publisher until there is room. One stuck reader then stops every
  publisher, and `publish` can no longer be called from an actor.

Refusing is recommended. `Stream.blocking` already handles stopping a
subscriber that is waiting, and closing it exactly once, on every backend, so
the hub adds only its queues.

## Stack

- [ ] **`spec-0057-hub`**: `Hub`, `HubRefused`, `publish`, `subscribe` on
      `Stream.blocking`, `close`, and `publishTo(hub)`.
      Done when: on Forks, Pekko and TestStreams all four of these hold:
      - early elements reach the first subscriber in order;
      - a full subscriber refuses `publish` for all;
      - a stopped subscriber no longer counts towards "full";
      - `close` ends every subscription `Done`.
- [ ] **`spec-0057-petshop`** (in petshop): replace `QueueBus` with `Hub`.
      Done when: `BusSpec` and `EventsSpec` pass unchanged against the hub.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What happens when one subscriber is full?** Answered, as recommended: refuse for
   everyone (see Why). The alternatives are to drop for that subscriber, or to
   let the caller choose a `Full` policy per hub. A policy per hub is easy to
   add later and hard to take back.
2. **What happens to elements published before the first subscriber?**
   Answered, as recommended: hold up to `capacity` of them for the first subscriber.
   Petshop depends on this, because its relay starts before its projection
   does. The alternative is to drop them.
3. **What should it be called: `Hub`, `Bus` or `Topic`?** Answered, as recommended: `Hub`.
   Pekko users already know the word, and `Bus` and `Topic` suggest a broker
   in another process.
4. **Should `publishTo(hub)` exist, or only `publish`?** Answered, as recommended: include it.
   It is one line on top of `mapOrFail`, and it makes a `Full` refusal part
   of the stream's failure type instead of something the caller has to check.
   (It is not `via`, which already composes a flow.)

## Decided while building

- **A subscription on TestStreams waits through its turns.** A worker on a test's
  clock that blocks holds its turn, so `start` never settled while a
  subscription waited. Core gains `Waiting` (behind `@StreamSpi`): TestStreams
  installs one on each worker, and a hub subscription that finds one parks until
  its queue has something, and `publish`, `close` and a stop tell it to look
  again. Forks and Pekko install nothing, and a subscription blocks its thread
  there as `Stream.blocking` always has.

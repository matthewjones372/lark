# 0141 — A publisher on either side

## Problem

Many JVM libraries speak Reactive Streams through the JDK's `java.util.concurrent.Flow`: R2DBC rows, the JDK
`HttpClient`'s body subscribers, the AWS SDK v2's async bodies, Reactor and Mutiny through their `Flow` adapters.
`lark-stream` has no way in or out. A service on Forks that wants one of them either drops to Pekko for
`JavaFlowSupport`, or writes its own bridge, and a hand-written subscriber easily gets demand wrong: it
requests `Long.MAX_VALUE` and buffers without limit, which is exactly the backpressure lark exists to keep.

## Not doing

- **No `org.reactivestreams` dependency in main code.** JDK `Flow` only. Libraries on the older interfaces
  have `FlowAdapters` in the same jar as the TCK.
- **No kotlinx `Flow`.** 0138 already leaves that to `runBlocking`.
- **No Pekko-native compile** through `JavaFlowSupport`. The same code runs on every backend; a native
  compile is a later optimisation if the benchmark asks for one.
- **No hot publisher.** `toPublisher` starts a run per subscriber. A hot one is `publishTo(hub)` (0057)
  followed by `hub.subscribe().toPublisher(...)`.

## Shape

Both in `lark-stream`:

```kotlin
// in: a publisher read with bounded demand
val rows: Stream<Nothing, Row> = Stream.fromPublisher(connection.createStatement(sql).execute().flatRows(), prefetch = 32)

// out: a stream handed to anything that subscribes
val body: Flow.Publisher<ByteBuffer> = chunks.toPublisher(Forks())
HttpRequest.BodyPublishers.fromPublisher(body)
```

- **`fromPublisher(publisher, prefetch = 16)`** is `Stream.blocking` (0055), so it needs no new node:
  - `open` subscribes with a subscriber that holds a queue of `prefetch` and requests `prefetch`;
  - `next` takes from the queue and requests `max(1, prefetch / 2)` more each time that many have been
    taken, as Pekko batches its demand; a `prefetch` below 1 is refused when the stream is built;
  - `wake` cancels the subscription and puts an end marker in the queue; `close` cancels, once.
  - `onError(t)` ends the run `Died` with `t`, as a failed `fromStage` does. `onNext` beyond what was
    requested is a publisher's bug and ends the run `Died` with an `IllegalStateException`.
- **`toPublisher(backend)`** answers a cold `Flow.Publisher<A>`. Each `subscribe` starts one run of the
  stream on `backend`:
  - the run's last stage parks while demand is zero, so the stream pulls only what was requested;
  - demand is an `AtomicLong` capped at `Long.MAX_VALUE` (§3.17), and `request(n <= 0)` stops the run and
    signals `onError(IllegalArgumentException)` (§3.9);
  - `cancel` is the run's `stop()`;
  - `Done` is `onComplete`, a declared failure `e` is `onError(DeclaredFailure(e))`, a defect is
    `onError` with the defect itself.
  - `onNext` is always called from the run's one pulling thread, so signals never overlap (§1.3).

## Why this shape

Reading a publisher is a blocking source with a resource to cancel, which is what `Stream.blocking` already
settles on every backend, `stop()` included. The alternative, a new `Node.FromPublisher` each backend
compiles, would let Pekko use its own `JavaFlowSupport.Source`, but it costs a compile per backend for a
saving nobody has measured. Recommended: build on `blocking`. A cold `toPublisher` matches what a
description is in lark (each run is fresh), and `Hub` already covers the hot case. `toPublisher` is the
push side of 0140's `toSequence`: the same `backend` parameter and the same parked run, with requested demand
where `toSequence` has a buffer, so the two should share that hand-off rather than each write one.

## Stack

- [ ] **`spec-0141-from-publisher`**: `Stream.fromPublisher` on `Stream.blocking`, with batched demand.
      Done when: on Forks, Pekko and TestStreams, a `SubmissionPublisher` of 10,000 elements read through a
      slow stage never has more than `prefetch` outstanding, and a `stop()` cancels the subscription.
- [ ] **`spec-0141-to-publisher`**: `Stream.toPublisher(backend)`, and the Reactive Streams TCK
      (`reactive-streams-tck-flow`, test scope) in `lark-stream-parity`.
      Done when: the TCK's `FlowPublisherVerification` passes for `toPublisher` on Forks and on Pekko.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-stream-parity:test --tests '*Publisher*'
```

## Open questions

1. **Should a publisher's `onError` be a defect or a declared failure?** Answered, as recommended: a defect, so
   `fromPublisher` is `Stream<Nothing, A>` like `fromStage`. The alternative is
   `Stream<Throwable, A>`, which lets `catchAll` see it but makes every caller name a type it rarely handles.
2. **Where does the parked last stage of `toPublisher` run on Pekko?** Answered, as recommended: on the blocking-IO
   dispatcher, as `Stream.blocking` does, so a subscriber that requests nothing for a while never holds a
   stream thread. The alternative is compiling to `JavaFlowSupport.Sink.asPublisher`, which is the native
   compile this spec leaves out.
3. **What should the default `prefetch` be?** Answered, as recommended: a parameter defaulting to 16, the
   same as Pekko's default input buffer and Forks' merge room. R2DBC drivers often want more; the parameter
   is there for them.
4. **Should `toPublisher` allow a second subscriber to the same publisher?** Answered, as recommended: yes, each gets
   its own run. The TCK allows either; refusing would be a surprise for a value that is only a description.

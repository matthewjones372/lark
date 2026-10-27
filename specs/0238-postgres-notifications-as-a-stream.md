# 0238 — Postgres notifications as a stream

## Problem

A service whose views should update live, such as a booking sheet that shows another desk's booking as it lands,
can have Postgres tell it: a trigger calls `pg_notify` in the changing transaction, and Postgres delivers the
notification after commit, in commit order, to every listener. Listening well is the fiddly part: one connection
held outside the pool, the driver's `getNotifications` reached without naming the driver, reconnecting with backoff
when the connection drops, and telling the consumer that notifications were missed meanwhile, so it can resync.
Doorlist, a venue booking service on 0.14.0, hand-rolls this as `LiveListener` and fans it out to server-sent events
(its ADR 0014). `surefold-jobs` has its own `Listener`, private to it, which wakes the worker, retries at a fixed
interval, reads no payload and does not report a gap.

## Not doing

- No HTTP: serving the events to browsers is Pelican's `sse`.
- No fan-out policy of our own: `surefold-stream`'s `Hub` fans a stream out to subscribers in one process.
- No delivery guarantee beyond Postgres's: what is notified while no one listens is gone; `Gap` says so.
- No triggers: what to notify, on which table, is the service's changelog.

## Shape

```kotlin
val notices: Stream<Nothing, Notice> = PostgresNotifications.listen(
    listenSource,                                    // a DataSource of its own, outside the pool
    channels = setOf("bookings_live"),
    reconnect = Schedule.exponential<Throwable>(1.seconds)
        .delayed { _, delay -> minOf(delay, 30.seconds) }.jittered(min = 0.5, max = 1.0),   // equal jitter
)
notices.map { notice ->
    when (notice) {
        is Notice.Received -> LiveEvent.parse(notice.payload)   // channel, payload, sending backend's pid
        Notice.Gap -> LiveEvent.Resync                          // reconnected: refetch everything shown
    }
}.publishTo(hub)

PostgresNotifications.notify(connection, "bookings_live", payload)   // pg_notify, refused past 7,999 bytes
```

- **One connection per run** of the stream: `listen` on each channel, then `getNotifications(timeout)` in a loop, as
  `Stream.blocking`, so stopping the run closes the connection. Run it once per process and share it through a `Hub`.
- **No driver named**: `PGConnection` is found by `unwrap` and reflection, as `surefold-jobs` does now; on another
  driver the stream fails at open with a message saying so.
- **Reconnect**: a lost connection is retried on `reconnect`; once listening again the stream emits `Notice.Gap`
  before anything else. A first connection that cannot be made is retried the same way, with no `Gap`.
- **Channels** are checked as plain identifiers at the call, since `LISTEN` takes no bound parameter.
- **Observability**: a log line and a `postgres.notify.reconnects` counter per reconnect.
- **`surefold-jobs`** drops its private `Listener` and wakes its worker from this stream, keeping its `listen`
  setting and its fallback to polling.

## Why this shape

A stream, because the consumers already are: a `Hub` per process feeding every open `sse` response, and `map`,
`filter` and `publishTo` over it. **Alternative:** a callback object with `start` and `close`, as Doorlist's is; it
needs no `surefold-stream`, but every consumer then rebuilds the stopping, backpressure and fan-out a stream has.
Recommended: the stream, in a module of its own, **`surefold-postgres-notify`**, as Doorlist asked for.

## Stack

- [x] **`spec-0238-listen`** ([#849](https://github.com/matthewjones372/surefold/pull/849)) — `surefold-postgres-notify`: `listen`, `Notice`, `notify`, the dependency test.
      Done when: on Postgres, a notification sent in a transaction that commits arrives once and one that rolls back
      never does, on two channels, and stopping the run closes the connection.
- [ ] **`spec-0238-reconnect`** — reconnect on the schedule, `Gap`, the counter.
      Done when: after `pg_terminate_backend` on the listening connection, the stream emits `Gap` and then the next
      notification, and reconnects follow the schedule on a `TestClock`.
- [ ] **`spec-0238-jobs`** — `surefold-jobs` on this module; `docs/postgres-notify.md`.
      Done when: the jobs suite's "a job enqueued to run now starts before the next poll" passes unchanged.

## Acceptance

```bash
./gradlew :surefold-postgres-notify:build :surefold-jobs:build
```

## Open questions

1. **Should `surefold-jobs` take `surefold-stream` on its classpath?** It is Surefold's own and has no external
   dependency. Recommended: yes; the alternative is two listeners to keep in step.
2. **The default `reconnect`?** Recommended: the sketch's, equal jitter from 500 ms to 30 s, as Doorlist's; jobs
   keeps its `poll` as the first delay.
3. **Payload as `String` or bytes?** Postgres's payload is text. Recommended: `String`; a service sends ids, not data.
4. **A `Gap` on the first connect too**, so a consumer started after events refetches once? Recommended: no; the
   consumer fetches at start anyway.

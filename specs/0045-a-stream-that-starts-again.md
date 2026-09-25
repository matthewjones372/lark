# 0045 — A stream that starts again

## Problem

A long-lived pipeline, such as an outbox relay or a subscriber, meets a defect
eventually: a timed-out ask, a client that threw, a bug on one element. The run
ends `Died`, lark logs it, and nothing starts it again. The petshop relay would
stop draining its outbox until the process restarted.

There is no `resume` on purpose (spec [0010](0010-a-defect-is-never-silent.md)),
because dropping the element that broke is how a pipeline loses data quietly.
Starting again is a different move: the pipeline is a description, and
materialising it again loses nothing that `resume` would have kept. Pekko's
`RestartSource` does it, but only for a `Source`, which is on the far side of
`toSource()`. That is declared only on `Stream<Nothing, A>`, so a stream still
carrying `E` has to give its failure type up to get it.

## Not doing

- **No restart on a declared failure.** `Failed(e)` is the pipeline saying how
  it ended, and `catchAll { this }` is already the way to go round again on
  purpose.
- **No resume, and no per-element supervision.** A restart starts the whole
  stream again, from its first element.
- **No new schedule vocabulary.** It is lark's `Schedule<Throwable, *>`, the one
  `retry` takes.
- **No restart on completion.** A stream that ended `Done` stays done.

## Shape

```kotlin
val relay: Stream<Nothing, Event> =
    Stream.tick(every, Unit)
        .mapPar(1) { _ -> shop.unsent() }        // a timeout here is a defect
        .mapConcat { it }
        .restartOnDefect(Schedule.exponential<Throwable>(100.milliseconds).jittered())
```

- `Stream<E, A>.restartOnDefect(schedule: Schedule<Throwable, *>): Stream<E, A>`.
- On a throwable that is not a declared failure, the schedule is stepped with
  it. `Continue(delay)` materialises the same description again after `delay`.
  `Done` lets the defect through, and the run is `Died` with that cause.
- A declared failure passes through untouched, as `Failed(e)`.
- Every restart is logged at warn through lark's logger, with the cause and the
  delay, because a defect that was restarted is still a defect.
- What was emitted before the defect stays emitted. A restarted `Stream.from`
  emits its elements again.

## Why this shape

An operator on `Stream` keeps `E` in the type, which is the thing
`RestartSource` cannot do from here. The description being a value is what
makes "the same stream again" mean something, so no factory lambda is needed.
Taking a `Schedule` reuses the backoff, jitter and limits lark already has,
rather than Pekko's `RestartSettings` beside them. The alternative, restarting
on any failure, would turn a declared `Failed` into a loop nobody asked for and
leave the `E` in the type as a lie.

## Stack

- [ ] **`spec-0045-restart-on-defect`**: `Stream.restartOnDefect`, the warn
      line, the docs table row.
      Done when: a stream whose first run throws on its third element and
      whose second does not ends `Done` with both runs' elements; a declared
      failure ends `Failed` without a restart; `Schedule.recurs(2)` over a body
      that always throws ends `Died` after three runs.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should the `Pipe` form exist?** Recommend not yet. A restart is a property
   of a whole source, and a `Pipe` restarted in the middle of someone else's
   stream would not replay the elements it lost.
2. **Name: `restartOnDefect`, or `retry` to match lark's?** Recommend
   `restartOnDefect`. lark's `retry` re-runs a body. This re-materialises a
   pipeline, and the name should say which failures count.
3. **Where does the warn line go when no lark logger is bound?** Recommend
   lark's default, as every other lark line does.

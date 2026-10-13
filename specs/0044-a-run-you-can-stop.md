# 0044 — A run you can stop

## Problem

`Run.run(system)` answers a `CompletionStage<Exit<E, R>>` and nothing else
([Run.kt](../lark-stream/src/main/kotlin/io/github/matthewjones372/lark/stream/Run.kt)).
A pipeline that never ends on its own, such as a `Stream.tick` feed or a
subscriber to a hub, has no way to be ended by whoever started it. Pekko's
`KillSwitch` is the tool for that. It is a materialised value, and a `Stream`
drops those by design.

What the petshop does instead is a flag and a `takeWhile { open.get() }`, which
ends the run at the next element rather than now, and a class of its own to
hold the flag and the stage together so the graph can release it. Its arrivals
feed does not bother, and ends when the actor system is torn down underneath
it.

## Not doing

- **No change to `run`.** It keeps answering the stage. Stopping is a second
  call for a second need.
- **No materialised values in general.** The kill switch is the only one this
  exposes, as a method rather than a type parameter.
- **No lark-app node type.** `lark-app-pekko` does not depend on `pekko-stream`,
  and `singleOf(start, release)` with the handle's `close` already does the job.
- **No abort with a failure.** `KillSwitch.abort(t)` would be a way to end a run
  `Died` on purpose, and nobody has asked.

## Shape

```kotlin
val relay: Running<Nothing, Done> =
    Stream.tick(every, Unit).mapPar(1) { _ -> shop.unsent() }
        .mapConcat { it }
        .runWith(Sink.foreach(::publish))
        .start(system)

relay.exit     // CompletionStage<Exit<Nothing, Done>>, as run answers
relay.stop()   // returns at once; the run ends Done with what the sink has
relay.close()  // stop, then wait for exit: a node's release

singleOf({ system: ActorSystem -> Relay(stream.start(system)) }, { it.running.close() })
```

- `Run<E, R>.start(system): Running<E, R>`.
- `Running<E, R>` holds `exit` and has `stop()`, and is `AutoCloseable`.
- A stopped run is `Exit.Done` with the sink's value at that point:
  `runCollect` answers what arrived before the stop.
- Elements in flight between stages when `stop()` lands are dropped, and a
  `mapPar` body still running is interrupted, as it is on any teardown.

## Why this shape

A separate `start` keeps `run`'s answer, and every caller of it, unchanged.
Folding the handle into `run` would force every existing caller to reach
through it for the stage. The kill switch sits right before the sink, so
`stop()` completes downstream and cancels upstream in one step, which is
Pekko's `UniqueKillSwitch.shutdown()` exactly. Ending `Done` rather than a new
`Exit.Stopped` keeps the `when` over `Exit` at three cases. A stop asked for is
not a failure, and the value the sink has is the value there is.

## Stack

- [ ] **`spec-0044-running`**: `Running`, `Run.start`, the kill switch in
      `Run`'s graph, and the docs table row.
      Done when: a tick stream started and stopped ends `Done` well before its
      next tick, `runCollect` stopped answers what it had, and `close()`
      returns only once `exit` has completed.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `close()` wait forever?** Recommend yes, through lark-pekko's
   `await`, so an interrupt of the closing thread cancels the wait rather than
   the run. A sink that never completes after `shutdown` is a sink bug, and a
   timeout here would hide it.
2. **Should `stop()` on a run that already ended be a no-op?** Recommend yes,
   as `KillSwitch.shutdown` is, so a release in a `finally` needs no guard.
3. **Name: `start` or `launch`?** Recommend `start`: it reads as the opposite
   of `stop`, and `run` stays the name for "run and hand me the answer".

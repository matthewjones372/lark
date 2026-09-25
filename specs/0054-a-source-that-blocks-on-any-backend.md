# 0054 — A source that blocks, on any backend

## Problem

A client library that hands out records by blocking — a Kafka consumer's
`poll`, a JDBC cursor, a queue's `take` — has no backend-neutral way in. Every
core builder is a value already in hand (`from`, `single`, `fromStage`), and
the only source that can hold something open is `Stream.hooked` in
`lark-stream-pekko` (0052), which only Pekko runs.

On Forks, `Stream.from(iterable)` over a blocking iterator runs fine but can
never be cleaned up. A run's `stop()` sets a flag that is read between
elements, and no source is told the run has ended. A consumer that is never
closed stays in its group until its session times out. A `poll` waiting on a
quiet topic never returns, so `stop()` never lands.

## Not doing

- **No new backend behaviour for existing sources.** `from`, `tick` and the
  rest compile exactly as they do.
- **No async source.** `fromStage` covers one value; a callback-driven
  source is a later spec.
- **No Pekko-only features in the core.** `Stream.hooked` stays in
  `lark-stream-pekko` for sources that are Pekko `Source`s already.

## Shape

```kotlin
val lines: Stream<Nothing, String> =
    Stream.blocking(
        open = { BufferedReader(FileReader(path)) },
        next = { reader -> reader.readLine() },    // null ends the stream
        wake = { reader -> reader.close() },       // stop(): make a blocked next return or throw
        close = { reader -> reader.close() },      // once, whichever way the run ended
    )

lines.runFold(0) { n, _ -> n + 1 }.run(Forks())               // or PekkoStreams(system), or TestStreams(clock)
```

- `Stream.blocking(open, next, wake, close)` in `lark-stream`, a new
  `Node.Blocking`. `open` runs once per run, `next` is called only by the one
  thread that pulls it, and `close` runs exactly once after the last `next`.
- `wake` is how `stop()` reaches a `next` that is blocked. It may be called
  from any thread, and a `next` it interrupts may return `null` or throw; a
  throw after `wake` ends the run `Done`, not `Died`.
- **Forks:** `next` runs on the pulling virtual thread. `stop()` calls `wake`,
  and `drain`'s `finally` calls `close`.
- **Pekko:** `Source.unfoldResource(open, next, close)` on Pekko's blocking-IO
  dispatcher, so a blocked `next` never holds a stream thread. `stop()` calls
  `wake` as a drain, and so does a cancel from downstream: Pekko reads ahead,
  so a satisfied `take` finds the source already blocked on its next read.
- **Every backend:** a run's exit completes after `close`, so a caller that
  waited for it finds the resource closed. A `close` that throws ends an
  otherwise `Done` run `Died`.
- **TestStreams:** `next` runs on the worker taking its turn, as `Elements`
  does.

## Why this shape

`open`/`next`/`close` is Pekko's `unfoldResource` exactly, which is the part
of a blocking source Pekko already got right, and it is the loop Forks runs
anyway. `wake` is the one piece neither has: without it a blocking `next` is a
`stop()` that never returns. The alternative, a core `RunHooks` any source can
register with, is more general but hands every source author the ordering
problems `wake` settles once.

## Stack

- [ ] **`spec-0054-blocking-node`**: `Stream.blocking`, `Node.Blocking`,
      render and `canFail`, and Forks and TestStreams running it.
      Done when: on Forks, a `stop()` while `next` is blocked returns, the
      run ends `Done`, and `close` ran once.
- [ ] **`spec-0054-blocking-pekko`**: the Pekko compile on the blocking-IO
      dispatcher, with `wake` on the kill switch.
      Done when: the parity suite runs the same `blocking` description on
      Pekko, Forks and TestStreams with the same answer.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `next` return `A?` or an `Option`?** Answered: `A?`. `A : Any`
   already, so `null` cannot be an element, and it reads like `readLine`.
2. **May `open` be called again by `restartOnDefect`?** Answered: yes, with
   `close` run on the failed resource first. A restart is a new run of the
   source.
3. **Should Forks refuse a run with two blocking sources (`zip`)?** Answered:
   no. Forks pulls them in turn on one thread, which is correct, only slow.

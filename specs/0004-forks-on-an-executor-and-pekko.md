# 0004 — Forks on an executor, and Pekko

## Problem

Every fork lark makes is `Thread.ofVirtual().start`, and there is no other
choice. A Pekko application already has one place that names, sizes and
instruments its threads — the dispatcher config — and a lark body running
there should be on threads that config owns, not on unnamed ones beside them.
The same body, calling Pekko, gets a `CompletionStage` back from everything
(the HTTP client, a `Source` run to a `Sink`, an `ask`) and joins it by hand,
unwrapping `CompletionException` and leaking the stage when the request is
cancelled. Spec 0003 named the first as `on:` and left it; Pelican's spec 0038
drafted the second in `pelican-lark`, which is the wrong home — it needs
Pekko, not Pelican.

## Not doing

- **No Pekko-native combinators.** Branches that do not block, composed as
  stages, cancelled through futures: a different programming model, without
  `raise`/`bind` in a branch and without "drop the `suspend`". Pekko 1.1's
  virtual-thread executor makes "on Pekko" mean "virtual threads Pekko's
  config owns", and that is what this spec delivers.
- **Nothing of Pelican.** `pelican-lark` will depend on `lark-pekko`.
- **No Streams combinators.** `Source.parMap` has backpressure and partial
  results in it; its own spec if a pipeline asks.
- **No `default-dispatcher`.** A blocking body on a fork-join pool is the
  thing Pekko's own documentation warns about; lark refuses it rather than
  documents it.

## Shape

```kotlin
val lark = system.larkDispatcher("lark")          // pekko.actor.lark { executor = "virtual-thread-executor" }

fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip(on = lark, { users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}

getQuote handledRaising(on = lark) { id ->
    val res  = http.singleRequest(HttpRequest.GET(url)).await()             // parks this virtual thread only
    val rows = Source.from(ids).via(price).runWith(Sink.seq(), system).await()
    ensure(res.status().isSuccess()) { raise(upstream(id)) }
    rows
}
```

- **In `lark`**, one seam: every forking combinator — `flock`, `async`,
  `parZip`, `parMap`, `raceN`, `parZipOrAccumulate`, `parMapOrAccumulate`,
  `timeout` — takes `on: Executor` as a defaulted first parameter, in the
  position arrow-fx gives `context: CoroutineContext`. The default is a
  virtual thread per fork, as today; nothing compiled against 0003 changes.
  `Fork` clears the interrupt flag when its body ends on a borrowed thread, so
  a cancel that lands as the body leaves never reaches the executor's next
  task — the guard `runRising` already has, moved to where every fork runs.
- **`lark-pekko`**, a leaf module: `pekko-actor` and `lark`, nothing else,
  asserted by its classpath test.
  - `ActorSystem.larkDispatcher(id: String = "lark"): Executor` (classic
    and typed receivers): looks the dispatcher up and refuses one whose
    executor is `fork-join-executor` or `thread-pool-executor` with a message
    that names the config key and the two settings that are accepted —
    `virtual-thread-executor`, or `PinnedDispatcher`.
  - `CompletionStage<T>.await(): T` and `scala.concurrent.Future<T>.await(): T`:
    join on the calling virtual thread; a failed stage rethrows its cause,
    not the `CompletionException`; an interrupt while waiting cancels the
    stage (`cancel(true)`) and rethrows the `InterruptedException`; a value
    arriving after the interrupt is discarded.
  - The README's Pekko section: the dispatcher block above, what parks, what
    is cancelled, and that `Sink.seq` still buffers the whole stream.

## Why this shape

A parameter rather than a scoped setting, because it is what arrow-fx does
(`parZip(Dispatchers.IO, …)`) and because a thread-local or `ScopedValue`
would have to be re-established on every fork — one more thing to get wrong,
for the benefit of not typing `on =`. A leaf module rather than a second
implementation, because the virtual-thread code is a dozen lines and the
combinators are not; the executor is the only thing Pekko needs to supply.
The `await()` bridge moves here from Pelican's 0038 because it depends on
Pekko alone and a Pekko application without Pelican wants it too.

## Stack

- [ ] **`spec-0004-on`** — `on: Executor` on every forking combinator;
      `Fork` clears the flag on a borrowed thread.
      Done when: a `parZip(on = singleThreadExecutor)` runs its branches
      there, a cancelled fork leaves the executor's next task uninterrupted,
      and every 0003 test passes unedited.
- [ ] **`spec-0004-pekko-dispatcher`** — the module, `larkDispatcher`, the
      refusal, the classpath test.
      Done when: a `virtual-thread-executor` dispatcher is accepted and a
      `fork-join-executor` one refused with the config key in the message.
- [ ] **`spec-0004-await`** — `await()` for `CompletionStage` and `Future`,
      the cancellation bridge, the README section.
      Done when: a handler awaiting a never-completing stage, interrupted,
      cancels that stage; and a stage failing with `X` reaches the body as
      `X`.

Pelican follows: spec 0038 becomes "depend on `lark-pekko`", and
`handledRaising(on = system.larkDispatcher())` is its example.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Parameter or scoped setting?** Recommended: parameter, as above.
2. **Refuse a fork-join dispatcher, or warn?** Recommended: refuse. A warning
   in a log is read after the pool is starved.
3. **Pekko version and artifact.** Recommended: `pekko-actor` 1.1.x only —
   `virtual-thread-executor` arrived in 1.1 — with the typed receiver going
   through `classicSystem()` rather than adding `pekko-actor-typed`.
4. **Does `timeout` take `on:`?** Its sleeper is a fork too. Recommended: yes,
   for consistency, defaulted like the rest.

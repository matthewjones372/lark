# 0138 — A future on either side

## Problem

Some libraries a service needs answer asynchronously: a `CompletableFuture` from the JDK's `HttpClient` or the
AWS SDK v2, a `suspend` function from Ktor's client or the AWS SDK for Kotlin. Some callers want one back:
a Ktor route, a Spring `@Async`, a Vert.x handler. Today each service writes its own bridge, and the obvious ones
are wrong in quiet ways:

- `future.get()` wraps the cause in `ExecutionException`, so a `catch` by name misses it, and a lark cancel
  leaves the future running with nobody waiting.
- `CompletableFuture.supplyAsync { lark() }` never interrupts on `cancel(true)` (the JDK says the flag has no
  effect), so the caller cancels and lark's forks run to the end.
- Calling lark straight from a coroutine blocks a `Dispatchers.Default` thread, and the app stalls under load.

The right `await` exists, but in `lark-pekko`, though it uses nothing from Pekko.

## Not doing

- **No coroutines anywhere.** No `lark-coroutines` module. Coroutine code uses kotlinx's own `runBlocking` and
  `CompletionStage.await()` around the two functions below; the cookbook shows how.
- **No `Flow` bridge to `lark-stream`.** `runBlocking { flow.collect { … } }` covers a use until one needs more.
- **No context onto a library's own threads.** Code that awaits stays on its thread and keeps its bindings; log
  lines and spans a library writes on threads of its own go without them.
- **No executor parameter on `larkFuture`.** One virtual thread per call, until someone asks.

## Shape

Both in `lark`, JDK only:

```kotlin
// lark code calling anything that answers later
val res = http.sendAsync(req, ofString()).await()      // CompletionStage<T>.await(): T
val n   = pool.submit(task).await()                    // Future<T>.await(): T

// anything calling lark
fun adopt(id: PetId): CompletableFuture<Either<AdoptionError, Adoption>> = larkFuture { shop.adopt(id) }

// coroutines, with kotlinx's own functions and nothing of lark's beyond the two above
val adoption = larkFuture { shop.adopt(id) }.await()            // kotlinx await: a coroutine cancel cancels it
val chip     = runBlocking { http.get("/chips/$id").body<Chip>() }
```

- `await()` parks the calling thread, throws the cause rather than `ExecutionException`, and cancels the future
  when interrupted, as `lark-pekko`'s does today.
- `larkFuture { }` runs the block on a fresh virtual thread and answers a `CompletableFuture` whose `cancel`
  interrupts that thread. kotlinx's `await` cancels the future when its coroutine is cancelled, so a Ktor
  disconnect reaches lark's forks with nothing coroutine-shaped in lark.

## Why this shape

Two functions with no dependency cover futures and coroutines both, because kotlinx already converts a coroutine
cancel into a future cancel. A coroutine module would be three more names and a dependency to save one rule:
no `raise` inside `runBlocking`, since Arrow's raise is a `CancellationException` there. The cookbook states the
rule. `larkFuture` rather than `future`: kotlinx's `CoroutineScope.future { }` would win inside a coroutine scope.

## Stack

- [ ] **`spec-0138-await`**: `CompletionStage.await()` and `Future.await()` in `lark`; `lark-pekko` keeps only the
      Scala `Future` one, and its callers change their import.
      Done when: tests show the cause is thrown unwrapped and an interrupt cancels the future, in `lark`.
- [ ] **`spec-0138-lark-future`**: `larkFuture { }`.
      Done when: a test shows `cancel(true)` interrupts the block and stops a fork inside it, and a test with
      kotlinx on the test classpath only shows a cancelled coroutine does the same.
- [ ] **`spec-0138-cookbook`**: four recipes and four rows in "What to reach for" (below).
      Done when: every recipe compiles in `example/`.

Recipes: *Wait for a future*, *Hand back a future*, *Call a coroutine library*, *Be called from coroutines*.

## Acceptance

```bash
./gradlew build
```

## Open questions

Answered when the spec was merged, each with the recommended answer:

1. `await` moves out of `lark-pekko` outright, with no deprecated forward. The README already says the next
   version breaks things.
2. The name is `larkFuture`, as it says whose rules run inside.
3. `larkFuture` carries the caller's lark bindings to its thread, through the same snapshot `flock` takes, so a
   span opened before it is the parent of what runs inside.

A `CompletableFuture` is both a `CompletionStage` and a `Future`, so `await` has a third overload on it, which
only breaks the tie.

# 0039 — A stream that runs on forks

## Problem

The only stream lark has is `lark-stream`, a typed view over Pekko Streams. A
service that isn't on Pekko pays for an actor system to get one. Its stages are
Pekko operators, so a stage body cannot `bind` without the `Failing` wrapper
turning a raise into an exception on Pekko's failure channel
([Stream.kt](../lark-stream/src/main/kotlin/io/github/matthewjones372/lark/stream/Stream.kt)).
A service without Pekko uses `Sequence` instead. It has the right shape and
fuses stages on one thread, but it has no bounded concurrent stage and no
bounded buffer between a fast stage and a slow one.

With 0037's forks and 0038's channels, the missing pieces are a few operators.

## Not doing

- **No change to `lark-stream`.** Pekko users keep it. The two share no types,
  and neither depends on the other.
- **No materialised values, graphs or fan-out.** Linear pipelines only. A
  second consumer is a second channel.
- **No push.** Everything here is pulled. A stage runs only when the stage after
  it asks.
- **No time-based operators** (`groupedWithin`, `throttle`) here. They are a
  spec of their own on `Schedule`.

## Shape

```kotlin
either {
    feed(rows)                                   // Iterable, Sequence, or a producing block
        .map { parse(it).bind() }                // on the pulling thread, fused
        .mapPar(8) { enrich(it).bind() }         // 8 forks, input order kept
        .buffer(256)                             // one fork upstream, a channel between
        .chunked(100)
        .forEach { db.insert(it) }               // runs it; answers Unit or raises
}
```

- `Feed<E, A : Any>` is a description until a terminal operator runs it inside a
  `Raise<E>`. Every stage body is `Raise<E>.(A) -> B`, so `bind` and `ensure` work
  unwrapped.
- Stages fuse on the thread that pulls, as `Sequence` does. Only `mapPar` and
  `buffer` fork, and each opens a `flock` with a `channel` between the forks.
- An early exit (`take`, `first`, a raise) cancels the channels upstream.
  Producers stop at their next `send`, and the scope joins every fork before the
  terminal returns.

## Why this shape

A synchronous pipeline with explicit async boundaries is what `Sequence` gets
right. Pekko makes every stage asynchronous by default and then fuses them back
together. Here concurrency appears only where it's written (`mapPar`, `buffer`),
backpressure is a `send` that parks, cancellation is `Channel.cancel`, and
failure is the scope's own `Raise`. A stream adds no rules the forks don't
already have.

The alternative is to keep `Stream<E, A>` and give it a second, fork-based
backend behind `lark-stream`'s API. Recommended against: that API exposes
`Source` (`toSource`, `Stream.from`), so the Pekko dependency cannot leave it.

## Stack

- [ ] **`spec-0039-feed`** — `Feed`, `feed`, fused `map`/`filter`/`take`/`chunked`,
      `forEach`, `toList`.
      Done when: a raise in `map` is the `Left`, and `take(3)` on an infinite feed
      returns.
- [ ] **`spec-0039-boundaries`** — `buffer` and `mapPar` on channels.
      Done when: `mapPar(8)` keeps input order with no more than 8 in flight,
      and a raise in one branch leaves no fork alive when the terminal returns.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **In `lark`, or in a module of its own?** It needs nothing but `lark`.
   Recommended: in `lark`, like `Schedule`.
2. **The name.** `Stream` clashes with `lark-stream`, `Flow` means something else
   to a Kotlin reader, and `Pipe` is taken. `Feed` is recommended. Pick another
   freely.
3. **Does `mapPar` keep input order?** Recommended: yes, with `mapParUnordered`
   later if a pipeline asks for it. This matches `lark-stream`.
4. **A producing block (`feed { emit(x) }`) as the way in?** Recommended: yes, as
   a fork with a channel of capacity 0, so `emit` parks until pulled.

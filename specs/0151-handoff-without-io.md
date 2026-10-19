# 0151: shard handoff without I/O

## Problem

`Placing` (`Placing.kt:52-255`) runs the shard handoff:

- **Winning:** a node that wins a shard asks every holder to release it
  (`win`), and starts the shard once all have answered (`startIfFree`).
- **Answering:** a node answers a release request only once it no longer runs,
  owns or is stopping the shard (`settle`).
- **Waiting:** it keeps messages for a shard with no ready owner, up to
  `KEEP_AT_MOST`, and asks again while any holder has yet to answer
  (`askAgain`).

That protocol is as subtle as Membership's, and it decides whether an entity
can run on two nodes at once. But it is welded to its I/O, done inline:

- **Remote sends:** `cluster.remote.remote(...)` (`:241`).
- **Timers:** `ctx.timers` for retry, drain and ask-again.
- **Shard managers:** `ctx.spawn`/`ctx.stop`, through `Hosting`.
- **Dead letters:** `cluster.flock.deadLetter` (`:253`).

So it can only be tested with whole flocks on real ports (`ShardingTest`,
`RebalanceTest`, `LateRegionTest`). Races such as a release that crosses a
view change, or an ask that a late region drops, are hit by chance, not by
construction. `Membership` shows the alternative: no thread and no clock,
driven through `tick` and `receive`, tested on a fake network (`Net`).

**Correction from the chat (Q12):** the region is spawned with no restart
schedule, so the state that `Placing` keeps outside the behaviour is never
stale after a restart. There is no bug there to lead with.

## Not doing

- Changing the handoff protocol, `Region` messages, or their wire format.
- Moving `HandOn` (spec 0095), which delivers to busy hosts here. It stays in
  the adapter.
- Changing `Rebalance`, which decides ownership. It is already pure.

## Shape

```kotlin
// lark-cluster, internal, pure
internal class Handoff<M : Any>(self: Node, shards: Int, owner: (Int, View, Moved) -> Node?) {
    fun viewed(view: View, moved: Map<Int, Incarnation>): List<Act<M>>
    fun asked(shard: Int, by: Node): List<Act<M>>
    fun released(shard: Int, by: Node): List<Act<M>>
    fun ended(shard: Int): List<Act<M>>
    fun route(envelope: Region.Envelope<M>, carried: Map<String, String>): List<Act<M>>
    fun retry(): List<Act<M>>
    fun askAgain(): List<Act<M>>
    val ready: Set<Int>
    val keeping: Int
}

internal sealed interface Act<out M> {
    data class Tell<M>(val to: Node, val step: Region<M>) : Act<M>      // Release, Released, a forward
    data class Deliver<M>(val shard: Int, val envelope: Region.Envelope<M>, val carried: Map<String, String>) : Act<M>
    data class Host(val shard: Int) : Act<Nothing>
    data class LetGo(val shard: Int) : Act<Nothing>
    data class Dead<M>(val envelope: Region.Envelope<M>) : Act<M>
    data class Timer(val key: TimerKey, val on: Boolean) : Act<Nothing> // retry, ask again
}
```

`Placing` becomes the adapter. It feeds each `Region` step to `Handoff` and
performs each `Act`. It keeps `HandOn`, the shard managers, the meters, and the
lookup from shard managers to shards.

## Why this shape

Returning acts, like `Membership.receive` returns sends, puts the protocol
behind a small interface that tests can drive message by message. The other
option is a port interface the core calls. That needs a fake adapter to
record calls, which tests the protocol through its side effects instead of
its answers. I recommend acts.

## Stack

- [ ] **`spec-0151-handoff`**: `Handoff` and `Act`, with direct tests. Each
      test drives one region by hand: win and wait, release after letting go,
      keep and retry, the hop limit, `KEEP_AT_MOST` turning envelopes into
      dead letters, and ask-again stopping once everyone has answered.
      Done when: `Placing` performs acts and has no handoff state of its own,
      and `ShardingTest`, `RebalanceTest`, `LateRegionTest` and
      `PlacementTest` pass.
- [ ] **`spec-0151-net`**: a test harness that wires several `Handoff`s
      through a fake network, with cut, heal and dropped messages, and runs
      the races: a view change mid-release, two nodes with views that
      disagree, and a late region.
      Done when: those three races are tests that pass for every seed in a
      fixed range.

## Acceptance

```bash
./gradlew :lark-cluster:test
```

## Open questions

1. We agreed in chat to make `Net` generic (Q25). It is built around
   `Membership`'s `tick(now)`. I recommend a small `Links` helper (cut, heal,
   drop) that `Net` and a new `Regions` harness share, rather than one
   generic `Net`.
2. `Carriers.capture()` happens inside `keep` today. `Handoff` takes `carried`
   as data, and the adapter captures it. Is losing the capture-at-keep timing
   acceptable? I recommend yes: the adapter captures it in the same step,
   before anything else runs.
3. Is the first entry over 200 lines? `Placing` is 255 lines, mostly moved. If
   so, I recommend splitting routing and keeping into an entry of their own,
   after the handoff itself.

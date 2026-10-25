# 0166 — A closing node sends what it already queued

## Problem

`Transport.close()` (`actor/surefold-actor-remote/.../Wire.kt`) closes every socket at once. It interrupts each
writer and drops whatever is still queued. So a frame told just before close is lost, even when its connection is
up and the frame was handed over in time.

A cluster leader trips on this. It removes a member that is leaving, and sends that member the one gossip that
says so: nobody probes a removed member, so nothing sends it again (`Membership.lead`). If the leader closes right
after, that gossip can be dropped. The leaving node then never learns it is out. It waits for a node that has gone,
and it gets out only when it downs itself or the other node, 20 s later (`Downing.stableAfter`). A rolling deploy
where both nodes stop together hits this.

`LeaveOnReleaseTest` failed this way once, in the snapshot workflow on `919e3df`, at
`LeaveOnReleaseTest.kt:69` (release took ≥ 20 s). Delaying each outbound write by 30 ms reproduces it every time
(3 of 3, at 22–23 s). Without the delay, the test releases in 2–3 s.

## Not doing

- **Resending in the membership.** Option 2: the leader keeps a removed member's gossip and resends it. That
  adds state for one message, and leaves every other frame told before close still lost.
- **Waiting for a peer that is not connected.** Frames for a peer whose connection is down, or not yet open, are
  dropped on close as now. The drain does not connect.
- **Acknowledged delivery.** A frame written to the socket counts as sent; nothing waits for the peer to read it.
- **Changing `LeaveOnReleaseTest`.** It is right as written, and becomes the regression test.

## Shape

```kotlin
class Transport(
    // ...
    private val drainWithin: Duration = 1.seconds,
) : AutoCloseable {
    override fun close() {
        // Stop taking new frames, stop accepting peers, then for up to drainWithin in all:
        // each connected writer writes and flushes what it holds; then sockets close, and what is left is dropped
        // and said to be, as today.
    }
}
```

- **New frames:** `send` after `close` has started drops the frame and reports it, as today.
- **Draining:** only a lane whose connection is up drains. A lane still connecting, or retrying, drops its frames
  at once.
- **The limit:** `drainWithin` is one deadline for the whole transport, not one per lane. A peer that stops
  reading cannot hold the close for longer than that.
- **Inbound:** inbound connections close as now. A node does not read after it has started closing.

## Why this shape

The frame is lost at the transport, so the transport is where to fix it. Every caller gets the fix: the cluster's
removal gossip, and any reply or tell sent just before shutdown. Bounding the drain keeps
close prompt when a peer is stuck. One second is room enough for frames that are already queued on a live
connection, and short next to the 30 s `leaveWithin` a cluster gives its own leave.

## Stack

- [x] **`spec-0166-drain`**: `Transport.close()` drains connected lanes for up to `drainWithin`, then closes as
      today.
      Done when:
      - a new `WireTest` case fails today and passes with the change: a frame sent the instant before `close`,
        on a connected lane whose writer is slowed, arrives at the peer;
      - a second case shows `close` returning within `drainWithin` against a peer that stops reading;
      - `LeaveOnReleaseTest` passes 3 of 3 with outbound writes delayed 30 ms (the same experiment as above, run
        locally and not committed).

## Acceptance

```bash
./gradlew :surefold-actor-remote:test :surefold-cluster:test :surefold-app-actor:test
./gradlew build
```

## Open questions

1. **Answered: the default `drainWithin`** is 1 s.
2. **Answered: `drainWithin` stays internal** to `Transport`; nothing has asked to tune it.
3. **Answered: the cluster adds no wait of its own.** The drain covers the removal gossip.

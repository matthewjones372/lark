# 0104 — A lane for the cluster's own traffic

## Problem

Each node sends to a peer through one bounded queue (`room`, 8,192 frames) and one TCP connection. The cluster's
own traffic uses the same queue as everything else: SWIM probes and acks, gossip, watches and `Terminated`. Under
load, a probe waits behind thousands of envelopes, so its ack comes back late, and a healthy node is suspected.
If the queue is full, the probe is dropped outright. lark-bank hit this at 300 transfers a second: acks arrived
late, the failure detector downed healthy nodes, and they restarted in a loop. The workaround was slower probing
(`probeEvery = 3s`, `ackWithin = 2s`), which also makes a real failure slower to notice. Pekko's Artery keeps its
control stream apart from ordinary messages for exactly this reason.

## Not doing

- **A lane for large messages.** Nothing in Lark sends frames big enough to need one yet.
- **UDP or any transport other than TCP.**
- **Priorities among ordinary messages.** Every envelope stays first in, first out.

## Shape

Two connections per peer, each with its own queue and writer:

- **Control:** membership (the cluster actor's frames), watches, `Terminated`, and anything exposed with
  `lane = Lane.Control`. It has a small queue (256 frames) and is never behind a data frame.
- **Data:** everything else, exactly as today.

```kotlin
node.expose(clusterActor, StepCodec, lane = Lane.Control)   // lark-cluster does this for its own actor
node.expose(region, wire)                                     // Lane.Data by default
```

- A frame's lane is its target path's lane, chosen when the path is exposed, so neither side has to agree on
  anything new in the frame. The receiving side takes the lane from the connection a frame arrives on.
- A lane's connection drops and retries on its own. A data connection that is down does not make the peer
  unreachable, because membership only looks at the control lane.
- Meters: `lark.remote.frames{lane}` and `lark.remote.dropped{lane}`.

## Why this shape

Two connections rather than one with a priority queue, because priority only reorders the queue. Once a large or
slow data write is in the socket's buffer, a control frame still waits behind it at the TCP level. A second
connection costs one socket per peer, which a cluster of hundreds can afford. The alternative, a priority queue on
one connection, is simpler and removes most of the queueing delay, but not the head-of-line blocking.

## Stack

- [ ] **`spec-0104-lanes`** — `Lane`, two outbound queues and connections per peer, `expose(…, lane)`, and the
      cluster actor on the control lane.
      Done when: with 8,192 data frames queued to a peer that reads slowly, a control frame still arrives within
      50 ms; and with the data queue full, no control frame is dropped.
- [ ] **`spec-0104-chaos`** — the bank's 300/s run with the default `probeEvery` and `ackWithin`.
      Done when: nothing is downed where, before this spec, healthy nodes were downed in a loop.

## Acceptance

```bash
./gradlew :lark-actor-remote:check :lark-cluster:check
```

## Open questions

1. **Should asks' replies go on the control lane?** Recommended: no. They are ordinary traffic, and many of them.
2. **One listening port, or two?** Recommended: one. The first frame on a new connection names its lane, so
   nothing changes for firewalls or Kubernetes Services.

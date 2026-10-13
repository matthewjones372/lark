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

- A frame's lane is its target path's lane, chosen when the path is exposed, and `WATCH` and `TERMINATED` are
  always control. The sender picks the connection by it; nothing new goes in the frame, and both lanes connect to
  the one listening port.
- A lane's connection drops and retries on its own. A data connection that is down does not make the peer
  unreachable, because membership only looks at the control lane.
- **The cluster's actor runs first.** A lane gets a probe to its node at once, but the ack still has to wait for
  the cluster actor's turn, and under load a node's runners have thousands of entities queued. So an actor spawned
  with `urgent = true` has its activations taken before any other; lark-cluster spawns its own actor so. This is
  Pekko's separate cluster dispatcher, without a second pool of threads.
- Meters: the existing `lark.remote.*` counters gain a `lane` tag.

**What the bank's run found.** With lanes and the urgent actor in place, the bank still lost bank-2 once per run.
The load was not the cause: bank-2 was downed seconds after it joined, before the load started. bank-1 names
bank-2 as a seed from its first second, and until bank-2's container exists, the name does not resolve. The JDK
remembers a failed lookup for 10 seconds (`networkaddress.cache.negative.ttl`), so bank-1 could not connect back
to bank-2 for up to 10 seconds after it joined. Its probes went nowhere, and `stableAfter = 10s` downed it. A
Kubernetes pod's DNS name appears the same way, only once it runs. A service that joins by name should set the
negative TTL to 0 before its first lookup; `Transport`'s documentation says so. A library does not set a JVM-wide
security property on its users' behalf.

## Why this shape

Two connections rather than one with a priority queue, because priority only reorders the queue. Once a large or
slow data write is in the socket's buffer, a control frame still waits behind it at the TCP level. A second
connection costs one socket per peer, which a cluster of hundreds can afford. The alternative, a priority queue on
one connection, is simpler and removes most of the queueing delay, but not the head-of-line blocking.

## Stack

- [x] **`spec-0104-lanes`** — `Lane`, two outbound queues and connections per peer, `expose(…, lane)`, and the
      cluster actor on the control lane.
      Done when: with 40,000 data frames told to a peer whose receiver is stuck, so the data queue is full and
      dropping, a control frame still arrives within 500 ms, and 200 more all arrive, in order (`LaneTest`).
- [x] **`spec-0104-urgent`** — `spawn(…, urgent = true)`, and the cluster actor spawned so.
      Done when: an urgent activation runs before a thousand ordinary ones queued ahead of it.
- [x] **`spec-0104-chaos`** — the bank's 300/s run with the default `probeEvery` and `ackWithin`.
      Done when: nothing is downed where, before this spec, healthy nodes were downed in a loop. Met at 400/s with
      `probeEvery = 1s` and `ackWithin = 300ms`, twice, once the bank stopped caching failed lookups (below).

## Acceptance

```bash
./gradlew :lark-actor:check :lark-actor-remote:check :lark-cluster:check
```

## Settled

1. **Do asks' replies go on the control lane?** No. They are ordinary traffic, and many of them.
2. **One listening port, or two?** One. The lane is the sender's choice of connection, so the protocol and the
   port are unchanged, and nothing changes for firewalls or Kubernetes Services.

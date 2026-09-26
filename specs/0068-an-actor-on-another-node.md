# 0068 — An actor on another node

## Problem

Every lark actor lives in one flock in one process. A service that wants an
actor on another machine has two choices today: Pekko's cluster, with its
actor system, or a queue or HTTP call written by hand, where a `Reply` cannot
follow the message. 0059 kept refs ready for this: an `ActorRef` equals by
`node / path / incarnation`, `Reply` is a narrow ref rather than a closure,
and `protocolFaults` refuses a message that could not cross. Nothing yet
crosses.

## Not doing

- **Membership, failure detection, sharding.** Which nodes exist, and when one
  is gone, is 0069; placing entities is 0070. Here a node is an address that
  someone gave you.
- **TLS and authentication.** A node listens on loopback unless told
  otherwise. A secure transport is its own spec.
- **Delivery guarantees beyond local ones.** At most once, and in order per
  sender and receiver, as 0059 promised. No acks or redelivery.
- **Automatic serialization.** No reflection and no bundled format.

## Shape

```kotlin
val codec: MessageCodec<Kennel> = kennelCodec   // bytes for each message, and refs as addresses
flock<Nothing, Unit> {
    node("shop-1", port = 25520)                // this flock is a node, listening
    val kennel = remote<Kennel>(Address("shop-2@10.0.0.7:25520", "/user/kennel", 0), codec)
    kennel.tell(Feed(50))
    kennel.ask(1.seconds) { Weigh(it) }         // the Reply crosses as an address, and comes back
}
```

- **Codecs.** `MessageCodec<M>` writes a message to bytes and reads it back,
  as `EventCodec` does for the journal. Refs inside a message (`Reply`,
  `ActorRef`) are written as addresses through the codec's `Refs`, and read
  back as refs that send over the wire.
- **The wire.** One TCP connection per pair of nodes, on JDK sockets and
  virtual threads, with no library under it. Frames carry a length, the
  target's path and incarnation, and the codec's bytes. A handshake names each
  node and its incarnation, so a restarted node's old refs are refused.
- **Delivery.** A frame for a path with no actor is a dead letter on the
  receiving node. A connection that is down drops what is told to it into the
  sending node's dead letters, as `Unreachable`, and reconnects with backoff.
  A full outbound buffer drops too: a remote `tell` never blocks the sender.
- **Watching.** `watch` of a remote ref answers `Terminated` when that actor
  stops, and when its connection has been down for `unreachableAfter`.
- **Tests.** Two flocks in one JVM over loopback, and the parity of a
  protocol's behaviour on one node and across two.

## Why this shape

lark's reason to exist is a small runtime you can read. So the transport is
JDK sockets on virtual threads: a blocking read per connection is what those
threads are for, and it adds no dependency. Pekko's Artery (Aeron or TCP, with
its own serialization registry) is the alternative. It is faster at scale, but
it is Pekko's actor system again. Explicit codecs follow the journal: bytes
through a codec the user owns, so a message's wire form is a decision, not a
side effect of reflection. A bundled format (kotlinx.serialization) could be an
optional module later.

## Stack

- [x] **`spec-0068-codec`** ([#154](https://github.com/matthewjones372/lark/pull/154)) — `MessageCodec`, `Refs`, and refs inside
      messages. Done when: a protocol with a `Reply` round-trips through bytes.
- [x] **`spec-0068-wire`** ([#155](https://github.com/matthewjones372/lark/pull/155)) — framing, handshake, one connection per pair,
      reconnect with backoff. Done when: frames cross loopback in order, and a
      dropped connection comes back without losing the pair's order after.
- [x] **`spec-0068-remote`** ([#156](https://github.com/matthewjones372/lark/pull/156)) — `node(…)`, `remote<M>(…)`, inbound dispatch,
      and ask across nodes. Done when: two flocks in one JVM tell and ask.
- [ ] **`spec-0068-unreachable`** — dead letters for unreachable nodes, and
      `watch` across nodes. Done when: stopping one flock gives the other
      `Terminated` within `unreachableAfter`.
- [ ] **`spec-0068-bench`** — remote tell and ping-pong against Pekko's Artery
      over TCP on loopback. Done when: the README says where lark stands.

## Acceptance

```bash
./gradlew build
./gradlew :lark-actor-benchmarks:jmh -PbenchmarkArgs=Remote
```

## Open questions

- **Its own module (`lark-actor-remote`) or in `lark-actor`?** Recommended:
  its own module, so a single-process service carries no socket code.
- **A full outbound buffer: drop, or block the sender like a full mailbox?**
  Recommended: drop to dead letters. A remote node is slower than any local
  actor, and a sender that blocks on the network is a stall across services.
- **Address form: `name@host:port` in `node`, or a separate field?**
  Recommended: in `node`, so `Address` keeps its shape and its equality.
- **Does `watch` across nodes need its own `Unreachable` signal?**
  Recommended: no, `Terminated`, as 0060 left it. Membership (0069) can tell
  "stopped" from "unreachable" once it knows which nodes are up.

Decided (2026-09-26): every open question goes as recommended. The transport
is its own module, `lark-actor-remote`; a full outbound buffer drops to dead
letters; a node's address is `name@host:port` in `Address.node`; and `watch`
across nodes answers `Terminated`.

Decided while building `spec-0068-wire` (2026-09-26), for editing: each
direction has its own connection, opened by the node that sends, so order per
sender and receiver needs nothing more than one writer per connection; Pekko's
Artery does the same. Frames wait, up to the queue's room, while a connection
opens, rather than being dropped because it is not yet up, and are dropped when
an attempt fails or a connection ends. Reconnection backs off on lark's clock.

Decided while building `spec-0068-remote` (2026-09-26), for editing:
- `node(name, port)` answers a `RemoteNode`, and `remote(address, codec)` is
  its member rather than the flock's, since a ref needs the node's transport.
- An actor is reached from another node once it is `expose`d with its codec,
  or once a ref to it crosses inside a message; a node offers nothing else.
- A ref or a reply inside a message carries the codec of what will be sent to
  it (`out.reply(reply, Codecs.int)`, `input.ref(codec)`), since the node that
  answers is the one that writes the answer. `Codecs` has the common ones.
- A frame's incarnation of 0 means whichever actor is at the path; any other
  is that one actor. A reply not answered in ten minutes is forgotten.
- The node closes with its flock, through a guardian actor's `Stopping`.

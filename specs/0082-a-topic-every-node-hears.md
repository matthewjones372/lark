# 0082 — A topic every node hears

## Problem

An event that several nodes care about has no path between them today. Examples
are a price change that every node's cache must drop, or a "user logged out"
that every node holding a session must act on. The receptionist (0062) finds
actors in one flock only; sharding (0070) reaches one entity by id, not
everyone interested. A service today does one of three things:
- keeps its own list of every member's cache actor and tells each;
- routes the event through Kafka for the sake of a fan-out inside one cluster;
- polls a database.

Pekko's answer is distributed pub-sub: a topic, anyone may subscribe on any
node, and a publish reaches them all.

## Not doing

- **Delivery guarantees beyond a tell's.** A publish is at most once to each
  subscriber, as a remote tell is (0068). A subscriber on a node that is
  unreachable misses what was published meanwhile. Reliable delivery is 0079's,
  for commands to one entity.
- **Send-to-one, or groups.** Pekko's `Send` (to one subscriber of a path) and
  group routing. A publish reaches every subscriber.
- **Ordering across publishers.** One publisher's messages reach each
  subscriber in the order published; two publishers are not ordered against
  each other.
- **Topics that persist or replay.** A subscriber hears what is published after
  it subscribed, and nothing before.

## Shape

```kotlin
val prices = cluster.topic("prices", PriceCodec)       // the same name and codec on every node

prices.subscribe(cacheActor)                             // until it stops, or unsubscribe
prices.publish(PriceChanged("sku-1", 499))               // every subscriber, on every member
```

- **One topic actor per node.** It sits at `/user/topic-<name>` and holds that
  node's subscribers, watching each so that one that stops is dropped.
- **Publish.** The local topic actor tells its own subscribers. It then sends
  the message once to the topic actor of every other `Up` member, which tells
  its subscribers and forwards it no further. One frame per member per
  publish, whatever the number of subscribers there.
- **Subscribers' nodes are not gossiped.** Every member gets every publish,
  whether or not it has subscribers. At a few dozen members that is cheaper
  than keeping a registry agreed, and a node that has none drops it on
  arrival.
- **Metrics.** `lark.topic.published{topic}`, `lark.topic.delivered{topic}` and
  `lark.topic.subscribers{topic}`, through 0081's flock metrics.

## Why this shape

Broadcasting to members rather than to known subscribers needs no state kept
in step across nodes: the view is already agreed, and a topic with no
subscribers somewhere costs that node one frame it drops. The alternative is
Pekko's: gossip each node's subscribers per topic, and send only where some
exist. It saves frames for a topic most nodes do not hear, but brings a second
registry to converge, and a publish that races a subscription is lost either
way. Recommended: broadcast to members, with a registry later if a service
shows the frames matter.

## Stack

- [x] **`spec-0082-local`** — `Topic`, the topic actor, subscribe, unsubscribe
      and publish on one node. Done when: three subscribers hear every
      publish in order, and one that stops is dropped without a dead letter.
      ([#221](https://github.com/matthewjones372/lark/pull/221))
- [ ] **`spec-0082-cluster`** — `cluster.topic(name, codec)`, the forward to
      every `Up` member, and the metrics. Done when: on three nodes with
      subscribers on two, 100 publishes from the third reach each subscriber
      once and in order, and a node that joins later hears what is published
      after it subscribes.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Broadcast to every member, or gossip where subscribers are?**
  Recommended: broadcast, as above.
- **Does a publisher hear its own publish when it subscribes too?**
  Recommended: yes; a subscriber is a subscriber, wherever it published from.
- **Topics outside a cluster.** Should `topic` also exist on a plain flock,
  for one node? Recommended: yes, as `flock.topic(name)`, the same actor with
  no forward; `cluster.topic` builds on it.
- **What a topic's messages need.** A codec for the kind, as sharding takes.
  Recommended: yes, the same `MessageCodec`, so Protobuf and Avro work
  unchanged.

Decided (2026-09-27): every open question goes as recommended. A publish is
broadcast to every `Up` member rather than to a registry of subscribers; a
publisher that subscribes hears its own publish; `flock.topic(name)` is the
one-node topic that `cluster.topic` builds on; and a topic's messages cross in
the kind's own `MessageCodec`.

Decided while building `spec-0082-local`: `flock.topic(name, forward)` takes
where each publish goes besides this node's subscribers, and the topic actor's
protocol, `TopicMessage`, is public, with `Arrive` for a message from another
node that is heard here and handed on no further. That is all `cluster.topic`
needs. The three metrics are recorded here, by the actor that owns them, not
in the cluster entry as drafted. A subscriber is watched as it subscribes, and
a stopped one is dropped once its `Terminated` is handled; a publish between
its stop and that is a dead letter, as any tell to a stopped actor is.

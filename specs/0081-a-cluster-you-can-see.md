# 0081 — A cluster you can see

## Problem

Since 0059, lark's actors, transport, cluster, sharding and reliable delivery
have recorded nothing. `lark` has a `Metrics` facade, which `lark-micrometer`
connects to Prometheus and the rest, but no counter or gauge is ever asked
for outside the stream operators. An operator running a sharded service today
cannot see:

- how many members are up or unreachable;
- whether this node leads;
- how many shards and entities a node hosts;
- how many messages are waiting for a shard to settle;
- how many dead letters there are, and why;
- how many commands a producer has not had confirmed.

Nor can a load balancer or Kubernetes ask whether a node is in its cluster at
all: a pod that never joined answers `/ready` the same as one that did. Today
the service polls `cluster.view` itself and exports what it thinks matters.

## Not doing

- **Tracing across nodes.** Carrying a trace context in a frame is its own
  spec; `lark-otel` already carries one across forks.
- **Per-actor or per-message metrics.** Nothing counts each tell or times each
  step. That would put a map hit on the hottest path lark has, and 0059's
  benchmarks are its reason to exist. Every metric here is per flock, per
  node, per peer, per kind or per producer.
- **A metrics endpoint or dashboard.** The backend is the service's, through
  `lark-micrometer` or any other `Metrics`.
- **Mailbox depth.** Reading every mailbox's size to publish it is the
  per-actor cost above; the shards' buffers stand in for it where it matters.

## Shape

```kotlin
// Nothing to call: every flock, node, cluster, region and producer records
// through lark's `metrics`, so a service that installed lark-micrometer sees:
//
// lark.actor.dead_letters{reason}                        counter
// lark.actor.restarts                                    counter
// lark.remote.frames{peer, direction}                    counter
// lark.remote.dropped{peer}                              counter
// lark.remote.connected{peer}                            gauge 0/1
// lark.cluster.members{status}                           gauge
// lark.cluster.unreachable                               gauge
// lark.cluster.leader                                    gauge 0/1
// lark.cluster.downed                                    counter
// lark.sharding.shards{kind}                             gauge, hosted here
// lark.sharding.entities{kind}                           gauge, running here
// lark.sharding.buffered{kind}                           gauge, waiting for an owner
// lark.delivery.unconfirmed{producer}                    gauge
// lark.delivery.resent{producer}                         counter
// lark.delivery.full{producer}                           counter

val app = actors() +
    single { actors: Actors -> actors.within { cluster(node("shop-1", 25520), seeds) } }
        .probe("cluster", timeout = 2.seconds) { cluster: Cluster -> cluster.ready() }
```

- **Where they record.** Gauges are set by the actor that owns the number, on
  its own step: the membership actor after each change, a region when a shard
  starts or stops, a producer when a command is kept or confirmed. A gauge is
  never read by walking actors.
- **Names and tags.** Dotted names under `lark.`, with tags for the dimension
  and a node tag bound once per flock through `metricTagged`. A kind's or a
  producer's name is the tag value as given.
- **`Cluster.ready()`** answers true once this node is `Up`, every member it
  sees is reachable, and its view has a leader. It answers false while the
  node is `Joining`, `Leaving`, downed, or in a minority waiting to be decided.
  It is the answer to `/ready`, through `lark-app`'s `probe`.

## Why this shape

Recording from the actor that owns each number keeps the cost where the
change already happens: a gauge set on a membership change or a shard start
costs nothing on a tell. The alternative is a snapshot API, `cluster.stats()`
and friends, that a service polls and exports itself. That is the most
flexible option, but every service writes the same exporter and picks its own
names. Recommended: record through `metrics`, and keep `view` for anything
richer.

## Stack

- [x] **`spec-0081-actors`** — dead letters by reason and restarts, per flock,
      in `lark-actor`. Done when: on `capturingMetrics`, a tell to a stopped
      actor and a restart are each counted once, and a tell's allocation in
      the ping-pong benchmark is unchanged.
      ([#216](https://github.com/matthewjones372/lark/pull/216))
- [x] **`spec-0081-remote`** — frames, dropped frames and connection state per
      peer, in `lark-actor-remote`. Done when: two nodes exchanging ten
      frames count ten each way, and a peer that goes away reads 0 and counts
      what was dropped for it.
      ([#217](https://github.com/matthewjones372/lark/pull/217))
- [x] **`spec-0081-cluster`** — membership gauges, the downed counter, and
      `Cluster.ready()`. Done when: three nodes read three `Up` with one
      leader among them, and after one crashes the others read one
      unreachable and `ready()` false until it is downed.
      ([#218](https://github.com/matthewjones372/lark/pull/218))
- [x] **`spec-0081-sharding`** — shards, entities and buffered envelopes per
      kind, and the producer's unconfirmed, resent and full. Done when: 0079's
      test ends with the survivors' shards summing to 256 and every producer
      gauge at zero, and the crash counted resends.
      ([#219](https://github.com/matthewjones372/lark/pull/219))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Record through `metrics`, or a stats API a service exports?**
  Recommended: record through `metrics`, as above.
- **A `node` tag on every metric, or none?** Recommended: bound once per flock
  from the remote node's name, so metrics of several nodes in one JVM, as in
  the tests, are told apart; a flock with no remote node has none.
- **Does `ready()` require every member to be reachable?** Recommended: yes.
  While a member is unreachable the cluster moves no one on, so shards whose
  owner is unreachable answer nothing. A node that says it is ready then is
  wrong for some of its requests. A service that prefers "up is enough" can
  probe `view` itself.
- **Mailbox depth.** Recommended: not now, as above; an actor that wants its
  own depth watched can publish it from its step.

Decided (2026-09-27): every open question goes as recommended. Metrics are
recorded through `metrics` by the actor that owns each number; a `node` tag is
bound once per flock from its remote node's name; `ready()` requires every
member it sees to be reachable; and mailbox depth waits.

Decided while building `spec-0081-actors`: a flock's guardian takes the
`metrics` and tags bound where it first stands, since an actor's step runs on
a runner that inherits neither, and holds each instrument by name and tags.
`Flock.counter`, `Flock.gauge` and `Flock.tagMetrics` are public, so the
remote, cluster and delivery modules record through their flock the same
way, and a node names itself once with `tagMetrics`. Dead letters are counted
where every one already passes, the guardian's handler; nothing on the tell
path changed, so the ping-pong benchmark was not rerun.

Decided while building `spec-0081-remote`: a `RemoteNode` holds each peer's
instruments once, so counting a frame is a map hit; a frame is counted out
when it is handed to the transport, and in when the transport reads it, so one
that is then dropped is counted both as sent and as dropped. The peer tag is
the peer's full `name@host:port`, since a seed is known by its address before
its name. `node(…)` tags its flock with the node's name. `CapturedMetrics`
keeps a counter by name alone, so the test measures through a `Metrics` of its
own, keyed by name and tags, and waits on the connection gauge rather than
polling it.

Decided while building `spec-0081-cluster`: the cluster actor sets the gauges
on each step that publishes a view, from the view it publishes, and counts
each `Downed` event it tells its subscribers, so each surviving node counts a
downing once. `members` has one gauge per status, set to zero where there are
none, so a status that empties reads zero rather than its last count. The
test crashes a node and finds `ready()` false while it is unreachable, then
true again once it is downed and removed; `ready()` without the reachability
check fails it.

Decided while building `spec-0081-sharding`:
- **Entities.** `entities(…)` takes `onRunning`, told +1 as an entity starts, -1 as it ends, and minus what is left as its manager stops. A kind's shards add to one count under a lock, so the gauge is never set from a stale sum.
- **Regions.** A region sets its shards and buffered gauges after each step. A singleton's region measures under the kind `singleton-<name>`.
- **Producers.** A producer's unconfirmed gauge is the room its `send`s have taken, so it costs nothing to keep. It is set as commands are kept and confirmed, and `Full` is counted where `send` gives up. On `testActors` the instruments do nothing.
- **The test.** 0079's crash test now also finds 256 shards and 200 running accounts between the survivors, nothing buffered, nothing unconfirmed, and resends counted. Dropping the +1 or the resend count fails it.

# Running a cluster

> Lark is a scratchpad, not a finished library — see
> [what this is](../README.md#what-this-is). Everything here works and is tested;
> none of it is settled.

`lark-actor` runs actors in one process. This page is what comes after: a
second node, then a cluster of them, then entities spread across it. It is
ordered the way a service meets these things, and each section says which spec
argued for the design, for the reasoning this page leaves out.

Every complete example below is compiled against the library by
`GuideExampleTest`, so what you read is what builds. Each is written as an
extension on the flock your service already has, since where that flock comes
from, `flock { }` or `lark-app-actor`'s `Actors.within`, is the service's
business.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-actor-remote:0.1.0") // two nodes
    implementation("io.github.matthewjones372:lark-cluster:0.1.0")      // everything else here
}
```

1. [Two nodes](#two-nodes)
2. [A cluster](#a-cluster)
3. [Entities](#entities)

## Two nodes

A node is a flock that listens on a port. An actor becomes reachable from other
nodes when you expose it with a codec, and another node reaches it through a
ref made from its address. Tell and ask work as they do in one process, with
one difference: across a network a message may be lost, so a remote tell is
at most once. A message that cannot be sent becomes a dead letter on the
sending side ([spec 0068](../specs/0068-an-actor-on-another-node.md)).

No serialisation library is chosen for you. A `MessageCodec` writes a message
field by field; a `Reply` or an `ActorRef` inside a message crosses as an
address, so an ask made on one node is answered from another.
`lark-actor-remote-protobuf` and `lark-actor-remote-avro` give you a codec
from generated classes instead
([spec 0071](../specs/0071-messages-in-protobuf-or-avro.md)).

<!-- cluster-remote -->
```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import kotlin.time.Duration.Companion.seconds

sealed interface Stock

data class Receive(val sku: String, val count: Int) : Stock

data class Count(val sku: String, val reply: Reply<Int>) : Stock

object StockCodec : MessageCodec<Stock> {
    override fun write(message: Stock, out: WireOut) = when (message) {
        is Receive -> {
            out.int(1)
            out.string(message.sku)
            out.int(message.count)
        }

        is Count -> {
            out.int(2)
            out.string(message.sku)
            out.reply(message.reply, Codecs.int)
        }
    }

    override fun read(input: WireIn): Stock = when (val tag = input.int()) {
        1 -> Receive(input.string(), input.int())
        2 -> Count(input.string(), input.reply(Codecs.int))
        else -> error("no stock message has the tag $tag")
    }
}

fun stock() = behaviour<Stock, Map<String, Int>>(emptyMap()) { _, counts, message ->
    when (message) {
        is Receive -> become(counts + (message.sku to (counts[message.sku] ?: 0) + message.count))
        is Count -> stay().also { message.reply(counts[message.sku] ?: 0) }
    }
}

/** On the warehouse node: the stock actor, where other nodes can reach it. */
fun Flock<Nothing>.warehouse() {
    val node = node("warehouse", 25520, host = "10.0.0.7")
    node.expose(spawn("stock", stock()), StockCodec)
}

/** On a shop node: a ref to the warehouse's stock, told and asked as if it were here. */
fun Flock<Nothing>.shop(): Either<AskFailure, Int> {
    val node = node("shop-1", 25520, host = "10.0.0.8")
    val warehouse = Node("warehouse", "10.0.0.7", 25520)
    val stock = node.remote(Address(warehouse.toString(), "/user/stock", 0), StockCodec)
    stock.tell(Receive("sku-1", 12))
    return stock.ask(5.seconds) { Count("sku-1", it) }
}
```

An address names a node, a path and an incarnation; an incarnation of `0`
means whichever actor is at that path when the message arrives.

### Nodes that know each other

A node listens on loopback by default, and speaks plain TCP. To run nodes on a
network you do not wholly trust, give each one a `Tls`: every connection, both
ways, is TLS 1.3 with a certificate on each side, and a peer's certificate
must name it, as a DNS subject alternative name, by the node name it gives
([spec 0073](../specs/0073-nodes-that-know-each-other.md)). A node whose
certificate another CA signed, or that claims another node's name, is refused
before it can send anything.

<!-- cluster-tls -->
```kotlin
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.remote.RemoteNode
import io.github.matthewjones372.lark.actor.remote.Tls
import io.github.matthewjones372.lark.actor.remote.node
import java.io.File
import java.security.KeyStore

/** This node's key and certificate, and the CA that signed every node's, from PKCS12 files the platform mounts. */
fun Flock<Nothing>.secured(password: CharArray): RemoteNode {
    fun store(path: String) =
        KeyStore.getInstance("PKCS12").apply { File(path).inputStream().use { load(it, password) } }
    val tls = Tls.mutual(keys = store("/etc/lark/node.p12"), password = password, trusted = store("/etc/lark/ca.p12"))
    return node("shop-1", 25520, host = "10.0.0.8", tls = tls)
}
```

`Tls(context)` takes an `SSLContext` you built yourself, for a store
`Tls.mutual` does not read; the name check is the same.

## A cluster

A cluster is nodes that agree who is up. `cluster(node, discovery)` joins the
nodes that `discovery` finds, or forms a cluster if this node is the lowest
seed and nobody answers. Membership is agreed by gossip, with no coordinator
([spec 0069](../specs/0069-nodes-that-agree-who-is-up.md)).

- **Seeds.** `Discovery.static` for a fixed list, `Discovery.dns` for every
  address a name resolves to, and `Discovery.srv` for SRV records.
  `lark-cluster-kubernetes` finds seeds through the pods API, and
  `lark-cluster-aws` through Cloud Map or ECS.
- **The view.** `cluster.view` is this node's current view: every member with
  its status, the ones some member cannot reach, and the leader. A member is
  `Joining`, `Up`, `Leaving`, `Down` or `Removed`.
- **Events.** `cluster.subscribe(ref)` tells an actor the view as it is, then
  each change: a member `Up`, `Unreachable`, `Reachable`, `Downed` or
  `Removed`.
- **Partitions.** A member that stops answering is unreachable, and nothing
  moves on until it is reachable again or downed. `Downing` decides which side
  of a partition stays: `keepMajority` by default, `staticQuorum` for a fixed
  size, or `lease` for two nodes or an even split, where a majority cannot
  decide. `stableAfter` is how long the partition must hold still first.
- **Ready.** `cluster.ready()` is true while this node is `Up`, has a leader,
  and can reach every member it sees. It is the answer to `/ready`
  ([spec 0081](../specs/0081-a-cluster-you-can-see.md)).

<!-- cluster-membership -->
```kotlin
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Downing
import io.github.matthewjones372.lark.cluster.MemberEvent
import io.github.matthewjones372.lark.cluster.cluster
import io.github.matthewjones372.lark.logInfo
import kotlin.time.Duration.Companion.seconds

/** A node of the shop's cluster, whose seeds are every address the headless service's name resolves to. */
fun Flock<Nothing>.joinShop(): Cluster {
    val node = node("shop-1", 25520, host = "0.0.0.0")
    val cluster = cluster(
        node,
        Discovery.dns("shop.default.svc.cluster.local", port = 25520),
        downing = Downing.keepMajority(stableAfter = 20.seconds),
    )
    val watcher = spawn(
        "membership-log",
        behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { logInfo("cluster: $event") } },
    )
    cluster.subscribe(watcher)
    return cluster
}
```

`Gossiping` sets how often members probe each other and how long an answer may
take. The defaults suit a busy node on a real network; tests that want a
cluster in a second set them lower, and a node under heavy load with them set
too low will see live members as unreachable.

## Entities

An entity is an actor with an id, run on whichever member owns it.
`cluster.sharding(kind, codec, passivateAfter) { id -> behaviour }` spreads a
kind's ids over 256 shards and each shard over the `Up` members, by a hash
every node computes the same way, so there is no coordinator to ask
([spec 0070](../specs/0070-an-entity-on-whichever-node-owns-it.md)).
`entity(id)` is a ref that stays good while the entity moves: tell it from any
node and the message reaches the owner. An entity that has had nothing for
`passivateAfter` stops, and starts again on its next message.

When a member joins or goes, only the shards it gains or loses move. The node
that wins a shard waits until the one that had it has stopped it, so an entity
never runs on two nodes at once. What arrives meanwhile is kept, up to a
bound, and delivered once the shard settles.

A singleton is one actor in the whole cluster, on the oldest `Up` member, and
moves by the same handoff when that member goes.

Every node calls `sharding` and `singleton` with the same arguments, including
nodes that should never host them: give those a role the others lack, and
place by it ([spec 0083](../specs/0083-nodes-that-do-different-work.md)).

<!-- cluster-entities -->
```kotlin
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.cluster
import io.github.matthewjones372.lark.cluster.sharding
import io.github.matthewjones372.lark.cluster.singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface Basket

data class Put(val sku: String) : Basket

data class Contents(val reply: Reply<List<String>>) : Basket

object BasketCodec : MessageCodec<Basket> {
    override fun write(message: Basket, out: WireOut) = when (message) {
        is Put -> {
            out.int(1)
            out.string(message.sku)
        }

        is Contents -> {
            out.int(2)
            out.reply(message.reply, Codecs.list(Codecs.string))
        }
    }

    override fun read(input: WireIn): Basket = when (val tag = input.int()) {
        1 -> Put(input.string())
        2 -> Contents(input.reply(Codecs.list(Codecs.string)))
        else -> error("no basket message has the tag $tag")
    }
}

fun basket() = behaviour<Basket, List<String>>(emptyList()) { _, skus, message ->
    when (message) {
        is Put -> become(skus + message.sku)
        is Contents -> stay().also { message.reply(skus) }
    }
}

/** Runs on every node of the shop: baskets on the nodes that serve them, one daily report in the whole cluster. */
fun Flock<Nothing>.shopFloor(roles: Set<String>): Pair<Sharded<Basket>, ActorRef<Basket>> {
    val node = node("shop-1", 25520, host = "0.0.0.0")
    val cluster = cluster(node, Discovery.srv("_lark._tcp.shop.internal"), roles = roles)
    val baskets = cluster.sharding("basket", BasketCodec, passivateAfter = 10.minutes, role = "baskets") { basket() }
    val report = cluster.singleton("daily-report", BasketCodec) { basket() }
    return baskets to report
}

/** From any node, whether or not it hosts baskets. */
fun addAndRead(baskets: Sharded<Basket>) =
    baskets.entity("customer-42").run {
        tell(Put("sku-1"))
        ask(5.seconds) { Contents(it) }
    }
```

An entity here loses its state when it moves or passivates. To keep it, make
the entity persistent.

package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import kotlin.random.Random
import kotlin.time.Duration

private val byAddress = compareBy<Node>({ it.host }, { it.port })

/**
 * One node's side of the membership protocol, as SWIM runs it: what it sends on each [tick] and for each message it
 * [receive]s. It does nothing on its own, and holds no thread or clock, so a test can run several on a network it
 * controls. Not thread-safe: one actor owns it.
 */
internal class Membership(
    private val self: Incarnation,
    private val seeds: () -> List<Node>,
    private val settings: Gossiping,
    private val downing: Downing,
    private val random: Random,
    private val startedAt: Duration,
) {
    private var gossip = Gossip.None
    private var seq = 0L
    private var probe: Probe? = null
    private val order = ArrayDeque<Incarnation>()
    private val relays = HashMap<Long, Relay>()
    private var joinAt = startedAt

    // The live members and the unreachable ones as they last changed, and when; and when each member was seen downed.
    private var shape: Pair<Set<Incarnation>, Set<Incarnation>>? = null
    private var shapeSince = startedAt
    private val downedAt = HashMap<Incarnation, Duration>()

    // When this node last heard from each member, directly or through a helper's probe.
    private val heard = HashMap<Incarnation, Duration>()

    private class Probe(val target: Incarnation, val seq: Long, val at: Duration) {
        var helped = false
        var answered = false
    }

    /** A probe this node makes for [requester], whose own probe was [seq]. */
    private class Relay(val requester: Incarnation, val seq: Long, val at: Duration)

    private val status: Status? get() = gossip.members[self]?.status

    private val active: Boolean get() = status?.isLive == true

    fun tick(now: Duration): List<Send> = when (status) {
        null -> joinOrForm(now)
        Status.Joining, Status.Up, Status.Leaving -> probing(now) + lead(now) + decide(now)
        Status.Down, Status.Removed -> emptyList()
    }

    fun receive(message: Swim, now: Duration): List<Send> {
        when (message) {
            is Swim.Ping -> heard[message.from] = now
            is Swim.Ack -> heard[message.from] = now
            is Swim.PingReq -> heard[message.from] = now
            is Swim.Join, is Swim.Welcome -> Unit
        }
        return answer(message, now)
    }

    private fun answer(message: Swim, now: Duration): List<Send> = when (message) {
        is Swim.Join -> admit(message.from)
        is Swim.Welcome -> welcomed(message)
        is Swim.Ping -> if (accepts(message.gossip) && message.to == self) pinged(message) else emptyList()
        is Swim.Ack -> if (accepts(message.gossip)) acked(message) else emptyList()
        is Swim.PingReq -> if (accepts(message.gossip)) asked(message, now) else emptyList()
    }

    /** Asks to leave: the leader removes this member once every member has seen it leaving. */
    fun leave() {
        if (status == Status.Joining || status == Status.Up) change(self, Status.Leaving)
    }

    /** Whether this node has been downed, by its own side or the other. */
    val downed: Boolean get() = status == Status.Down

    fun view(): View {
        val members = gossip.members.filterValues { it.status != Status.Removed }
            .map { (m, e) -> Member(m.node, m.uid, e.status, e.upNumber, m.roles) }
            .sortedWith(compareBy({ it.status == Status.Joining }, { it.upNumber }, { it.node.toString() }))
        return View(members, gossip.unreachable().mapTo(mutableSetOf()) { it.node }, leader()?.node)
    }

    /** Writes what this member runs of [kind] now, for every member to see (spec 0090). */
    fun report(kind: String, shards: Map<Int, ShardLoad>) {
        val last = gossip.loads[self]
        if (!active || last?.kinds?.get(kind) == shards) return
        val load = Load((last?.version ?: 0) + 1, last?.kinds.orEmpty() + (kind to shards))
        gossip = gossip.copy(loads = gossip.loads + (self to load))
    }

    fun balance(): Balance {
        val live = gossip.live()
        val loads = gossip.loads.filterKeys { it in live }.entries.associate { (m, load) -> m.node to load.kinds }
        return Balance(loads, gossip.moves.mapValues { it.value.to })
    }

    /**
     * Moves each shard of [kind] in [to] to the `Up` member at that node, or back to its hash owner if null (spec
     * 0090). Only the leader writes moves, and a shard sent to a member that is not `Up` stays where it is.
     */
    fun move(kind: String, to: Map<Int, Node?>) {
        if (leader() != self) return
        val up = gossip.members.filterValues { it.status == Status.Up }.keys.associateBy { it.node }
        val last = gossip.moves[kind]
        val next = to.entries.fold(last?.to.orEmpty()) { moved, (shard, node) ->
            if (node == null) moved - shard else up[node]?.let { moved + (shard to it) } ?: moved
        }
        if (next != last?.to.orEmpty()) write(kind, next)
    }

    private fun write(kind: String, moved: Map<Int, Incarnation>) {
        val moves = Moves((gossip.moves[kind]?.version ?: 0) + 1, moved)
        gossip = gossip.copy(moves = gossip.moves + (kind to moves))
        noteDigest()
    }

    /** Drops the moves to members that are no longer `Up`, whose shards are back with their hash owners. */
    private fun dropGone() {
        val up = gossip.members.filterValues { it.status == Status.Up }.keys
        gossip.moves.forEach { (kind, moves) ->
            if (!up.containsAll(moves.to.values)) write(kind, moves.to.filterValues { it in up })
        }
    }

    private fun accepts(other: Gossip) = active && other.origin == gossip.origin

    private fun isSelf(node: Node) = node.host == self.node.host && node.port == self.node.port

    private fun joinOrForm(now: Duration): List<Send> {
        if (now < joinAt) return emptyList()
        joinAt = now + settings.probeEvery
        val found = seeds()
        val others = found.filterNot(::isSelf)
        val lowest = found.minWithOrNull(byAddress)
        val waited = others.isEmpty() || now - startedAt >= settings.formAfter
        if (lowest != null && isSelf(lowest) && waited) {
            gossip = Gossip.None.copy(origin = self, members = mapOf(self to Entry(Status.Up, 1)))
            noteDigest()
            return emptyList()
        }
        return others.map { Send(it, Swim.Join(self)) }
    }

    private fun admit(joiner: Incarnation): List<Send> {
        if (!active) return emptyList()
        gossip.members.filter { (m, e) -> m.node == joiner.node && m != joiner && e.status.isLive }
            .keys.forEach { change(it, Status.Down) }
        val known = gossip.members[joiner]
        if (known == null) change(joiner, Status.Joining) else if (!known.status.isLive) return emptyList()
        return listOf(Send(joiner.node, Swim.Welcome(joiner, gossip)))
    }

    private fun welcomed(welcome: Swim.Welcome): List<Send> {
        if (status == null && welcome.to == self) {
            gossip = welcome.gossip
            noteDigest()
        }
        return emptyList()
    }

    private fun pinged(ping: Swim.Ping): List<Send> {
        merge(ping.gossip)
        return listOf(Send(ping.from.node, Swim.Ack(self, ping.seq, gossip)))
    }

    private fun acked(ack: Swim.Ack): List<Send> {
        merge(ack.gossip)
        val current = probe
        if (current != null && current.seq == ack.seq && current.target == ack.from) {
            current.answered = true
            observe(ack.from, reachable = true)
            return emptyList()
        }
        val relay = relays.remove(ack.seq) ?: return emptyList()
        return listOf(Send(relay.requester.node, Swim.Ack(ack.from, relay.seq, gossip)))
    }

    private fun asked(request: Swim.PingReq, now: Duration): List<Send> {
        merge(request.gossip)
        relays[++seq] = Relay(request.from, request.seq, now)
        return listOf(Send(request.target.node, Swim.Ping(self, request.target, seq, gossip)))
    }

    private fun probing(now: Duration): List<Send> {
        val out = mutableListOf<Send>()
        probe?.let { current ->
            if (!current.answered && !current.helped && now - current.at >= settings.ackWithin) {
                current.helped = true
                out += (others() - current.target).shuffled(random).take(settings.helpers)
                    .map { Send(it.node, Swim.PingReq(self, current.target, current.seq, gossip)) }
            }
            if (now - current.at >= settings.probeEvery) {
                if (!current.answered) observe(current.target, reachable = false)
                probe = null
            }
        }
        relays.values.removeIf { now - it.at >= settings.probeEvery }
        if (probe == null) {
            next()?.let { target ->
                probe = Probe(target, ++seq, now)
                out += Send(target.node, Swim.Ping(self, target, seq, gossip))
            }
        }
        return out
    }

    /** The next member to probe: each once, in an order shuffled afresh every time round. */
    private fun next(): Incarnation? {
        val others = others()
        order.removeAll { it !in others }
        if (order.isEmpty()) order.addAll(others.shuffled(random))
        return order.removeFirstOrNull()
    }

    private fun others(): List<Incarnation> = (gossip.live() - self).toList()

    /** The oldest reachable member that is `Up`: it alone moves members on, and only once every member agrees. */
    private fun leader(): Incarnation? {
        val unreachable = gossip.unreachable()
        return gossip.members.filter { (m, e) -> e.status == Status.Up && m !in unreachable }
            .entries.minWithOrNull(compareBy({ it.value.upNumber }, { it.key.node.toString() }))?.key
    }

    private fun converged(): Boolean {
        val mine = gossip.digests[self]?.hash
        return gossip.unreachable().isEmpty() && gossip.live().all { gossip.digests[it]?.hash == mine }
    }

    /**
     * Moves members on, if this node leads and every member agrees. A member it removes after leaving is sent the
     * gossip that removes it, since nobody probes a removed member, and it would otherwise never learn it is out.
     */
    private fun lead(now: Duration): List<Send> {
        gossip.members.filterValues { it.status == Status.Down }.keys.forEach { downedAt.putIfAbsent(it, now) }
        if (leader() != self || !converged()) return emptyList()
        var upNumber = gossip.members.values.maxOf { it.upNumber }
        val left = mutableListOf<Incarnation>()
        gossip.members.entries.sortedBy { it.key.node.toString() }.forEach { (m, e) ->
            when (e.status) {
                Status.Joining -> change(m, Status.Up, ++upNumber)
                Status.Leaving -> change(m, Status.Removed).also { left += m }
                Status.Down -> if (now - downedAt.getValue(m) >= downing.stableAfter) change(m, Status.Removed)
                Status.Up, Status.Removed -> Unit
            }
        }
        dropGone()
        return left.map { Send(it.node, Swim.Ping(self, it, ++seq, gossip)) }
    }

    /**
     * Once who is unreachable has held still for long enough, downs them, or this node's whole side if it does not
     * stay. A side that goes tells its members as it does, since a member that has downed itself answers no probe,
     * and one that went quiet first would look to the rest of its side like a new partition, and delay them.
     *
     * Every member counted on this side must have been heard from since the view last changed: a partition found
     * one member at a time would otherwise hold still between two probes, and be decided on half of it.
     */
    private fun decide(now: Duration): List<Send> {
        val live = gossip.live()
        val unreachable = gossip.unreachable()
        if (shape != live to unreachable) {
            shape = live to unreachable
            shapeSince = now
        }
        val side = if (self in unreachable) setOf(self) else live - unreachable
        if (!settled(now, unreachable, side)) return emptyList()
        if (stays(side, live)) {
            // Suspected by the others is not the same as lost: the side that stays never downs itself.
            (unreachable - side).forEach { change(it, Status.Down) }
            return emptyList()
        }
        side.forEach { change(it, Status.Down) }
        return (side - self).map { Send(it.node, Swim.Ping(self, it, ++seq, gossip)) }
    }

    private fun settled(now: Duration, unreachable: Set<Incarnation>, side: Set<Incarnation>): Boolean =
        unreachable.isNotEmpty() &&
            now - shapeSince >= downing.stableAfter &&
            (side - self).all { member -> heard[member]?.let { it >= shapeSince } == true }

    private fun stays(side: Set<Incarnation>, live: Set<Incarnation>): Boolean {
        val lowest = compareBy(byAddress, Incarnation::node)
        return when (downing) {
            is Downing.KeepMajority -> {
                val even = side.size * 2 == live.size
                side.size * 2 > live.size || (even && live.minWith(lowest) in side)
            }

            is Downing.StaticQuorum -> side.size >= downing.size

            is Downing.ByLease -> downing.lease.acquire(side.minWith(lowest).node.toString())
        }
    }

    private fun change(member: Incarnation, to: Status, upNumber: Int = gossip.members[member]?.upNumber ?: 0) {
        gossip = gossip.copy(members = gossip.members + (member to Entry(to, upNumber)))
        noteDigest()
    }

    private fun merge(other: Gossip) {
        gossip = gossip.merge(other)
        noteDigest()
    }

    private fun observe(subject: Incarnation, reachable: Boolean) {
        val key = Observation(self, subject)
        val last = gossip.observed[key]
        if ((last?.reachable ?: true) == reachable) return
        gossip = gossip.copy(observed = gossip.observed + (key to Seen(reachable, (last?.version ?: 0) + 1)))
    }

    private fun noteDigest() {
        val hash = gossip.hash()
        val last = gossip.digests[self]
        if (last?.hash == hash) return
        gossip = gossip.copy(digests = gossip.digests + (self to Digest((last?.version ?: 0) + 1, hash)))
    }
}

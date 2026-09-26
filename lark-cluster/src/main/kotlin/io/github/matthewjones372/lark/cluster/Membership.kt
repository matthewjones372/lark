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
    private val random: Random,
    private val startedAt: Duration,
) {
    private var gossip = Gossip.None
    private var seq = 0L
    private var probe: Probe? = null
    private val order = ArrayDeque<Incarnation>()
    private val relays = HashMap<Long, Relay>()
    private var joinAt = startedAt

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
        Status.Joining, Status.Up, Status.Leaving -> probing(now).also { lead() }
        Status.Down, Status.Removed -> emptyList()
    }

    fun receive(message: Swim, now: Duration): List<Send> = when (message) {
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

    fun view(): View {
        val members = gossip.members.filterValues { it.status != Status.Removed }
            .map { (m, e) -> Member(m.node, m.uid, e.status, e.upNumber) }
            .sortedWith(compareBy({ it.status == Status.Joining }, { it.upNumber }, { it.node.toString() }))
        return View(members, gossip.unreachable().mapTo(mutableSetOf()) { it.node }, leader()?.node)
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

    private fun lead() {
        if (leader() != self || !converged()) return
        var upNumber = gossip.members.values.maxOf { it.upNumber }
        gossip.members.entries.sortedBy { it.key.node.toString() }.forEach { (m, e) ->
            when (e.status) {
                Status.Joining -> change(m, Status.Up, ++upNumber)
                Status.Leaving, Status.Down -> change(m, Status.Removed)
                Status.Up, Status.Removed -> Unit
            }
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

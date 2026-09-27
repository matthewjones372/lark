package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.Next
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.counter
import io.github.matthewjones372.lark.actor.gauge
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.RemoteNode
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logWarn
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private const val CLUSTER = "cluster"

/** What the cluster actor handles: a message from another node's, a tick of its own, or a request from this node. */
internal sealed interface Step {
    data class Heard(val message: Swim) : Step

    data object Tick : Step

    data object Leave : Step

    data class Subscribe(val to: ActorRef<MemberEvent>) : Step

    data class Move(val kind: String, val to: Map<Int, Node?>) : Step
}

private val StepCodec = object : MessageCodec<Step> {
    override fun write(message: Step, out: WireOut) = when (message) {
        is Step.Heard -> SwimCodec.write(message.message, out)
        Step.Tick, Step.Leave, is Step.Subscribe, is Step.Move -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Step = Step.Heard(SwimCodec.read(input))
}

private typealias Subscribers = Set<ActorRef<MemberEvent>>

/**
 * [node] as a member of a cluster whose seeds [discovery] finds, until the flock closes, when it leaves first:
 * waiting up to [leaveWithin] to be out, before the flock stops its actors (spec 0080). A [leaveWithin] of zero
 * leaves nothing: the node goes as a crashed one does, and the others down it. [roles] say what this node was
 * started to do, and every member sees them in its view (spec 0083). The membership runs in an
 * actor of its own, which the nodes of the cluster reach at the same path on each, and from now on it decides when
 * a watch on another node's actor ends. A partition is resolved by [downing]; a node downed stops that actor, and its
 * subscribers hear it downed and every other member removed: whether the process ends is theirs to decide.
 */
fun <F> Flock<F>.cluster(
    node: RemoteNode,
    discovery: Discovery,
    gossiping: Gossiping = Gossiping(),
    downing: Downing = Downing.keepMajority(),
    leaveWithin: Duration = 30.seconds,
    roles: Set<String> = emptySet(),
): Cluster {
    require(roles.none(String::isBlank)) { "a role needs a name, was $roles" }
    val time = clock.get()
    val now = { time.now().let { it.epochSecond.seconds + it.nano.nanoseconds } }
    val membership = Membership(
        Incarnation(node.self, Random.nextLong(), roles),
        discovery::seeds,
        gossiping,
        downing,
        Random.Default,
        now(),
    )
    val cluster = Cluster(node.self, node, this, leaveWithin)
    val steps = Steps(node, membership, cluster, node.takeOverWatches(), now)
    val ref = spawn(
        CLUSTER,
        behaviour<Step, Subscribers>(emptySet()) { ctx, subscribers, step -> steps.step(ctx, subscribers, step) }
            .onStart { ctx -> ctx.timers.every(Step.Tick, gossiping.ackWithin / 2, Step.Tick) }
            .onSignal { _, subscribers, signal ->
                when (signal) {
                    is Signal.Terminated -> become(subscribers.filterTo(mutableSetOf()) { it != signal.ref })
                    Signal.Stopping -> stay()
                }
            },
    )
    node.expose(ref, StepCodec)
    cluster.actor = ref
    if (leaveWithin.isPositive()) {
        onClose {
            val left = cluster.stop(leaveWithin)
            if (!left) logWarn("${node.self} did not leave its cluster within $leaveWithin; closing anyway")
        }
    }
    return cluster
}

/** What the cluster actor does with each step: the membership's part, then what changed told to whoever hears it. */
private class Steps(
    private val node: RemoteNode,
    private val membership: Membership,
    private val cluster: Cluster,
    private val endWatches: (Node) -> Unit,
    private val now: () -> Duration,
) {
    fun step(ctx: Ctx<Step>, subscribers: Subscribers, step: Step): Next<Subscribers> {
        when (step) {
            is Step.Heard -> send(membership.receive(step.message, now()))

            Step.Tick -> {
                report()
                send(membership.tick(now()))
            }

            Step.Leave -> membership.leave()

            is Step.Move -> membership.move(step.kind, step.to)

            is Step.Subscribe -> {
                ctx.watch(step.to)
                changes(View.None, cluster.view).forEach(step.to::tell)
                return become(subscribers + step.to)
            }
        }
        publish(subscribers)
        return if (membership.downed) stop() else stay()
    }

    private fun report() = cluster.meters.forEach { meter ->
        meter.due(now())?.let { membership.report(meter.kind, it) }
    }

    private fun send(sends: List<Send>) = sends.forEach { (to, message) ->
        node.remote(Address(to.toString(), "/user/$CLUSTER", 0), StepCodec).tell(Step.Heard(message))
    }

    // Held once, and set on each step that publishes, by the one actor that owns the view (spec 0081).
    private val members =
        Status.entries.associateWith { status -> cluster.flock.gauge("lark.cluster.members", "status" to status.name) }
    private val unreachable = cluster.flock.gauge("lark.cluster.unreachable")
    private val leader = cluster.flock.gauge("lark.cluster.leader")
    private val downed = cluster.flock.counter("lark.cluster.downed")

    private fun measure(view: View, events: List<MemberEvent>) {
        val counts = view.members.groupingBy { it.status }.eachCount()
        members.forEach { (status, gauge) -> gauge.set((counts[status] ?: 0).toDouble()) }
        unreachable.set(view.unreachable.size.toDouble())
        leader.set(if (view.leader == node.self) 1.0 else 0.0)
        downed.increment(events.count { it is MemberEvent.Downed }.toDouble())
    }

    private fun publish(subscribers: Subscribers) {
        val next = membership.view()
        // A downed node is out of the cluster: every other member is gone as far as it is concerned.
        val gone = if (membership.downed) next.members.filter { it.node != node.self } else emptyList()
        val events = changes(cluster.view, next) + gone.map(MemberEvent::Removed)
        events.filterIsInstance<MemberEvent.Removed>().forEach { endWatches(it.member.node) }
        subscribers.forEach { subscriber -> events.forEach(subscriber::tell) }
        measure(next, events)
        cluster.publish(next, membership.balance())
    }
}

/** One node's membership of a cluster: the view it has now, and a way to leave. */
class Cluster internal constructor(
    val self: Node,
    internal val remote: RemoteNode,
    internal val flock: Flock<*>,
    internal val leaveWithin: Duration = Duration.ZERO,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val viewers = CopyOnWriteArrayList<(View) -> Unit>()

    /** What each kind that rebalances counts here, for the cluster actor to report (spec 0090). */
    internal val meters = CopyOnWriteArrayList<LoadMeter>()

    @Volatile
    var view: View = View.None
        private set

    internal lateinit var actor: ActorRef<Step>

    /** The load of the kinds that rebalance, as of [view]: published with it, and waited on as it is. */
    @Volatile
    internal var balance: Balance = Balance.None
        private set

    /** Asks to leave: the oldest member removes this one once every member has seen it go. */
    fun leave() = actor.tell(Step.Leave)

    /**
     * Whether this node is fully in its cluster, for a readiness probe (spec 0081): `Up`, with a leader, and every
     * member it sees reachable. While one is unreachable the cluster moves no one on, so the shards it owned answer
     * nothing; a node that is `Joining`, `Leaving`, downed or waiting for a partition to be decided is not ready.
     */
    fun ready(): Boolean {
        val now = view
        return now.members.any { it.node == self && it.status == Status.Up } &&
            now.leader != null &&
            now.unreachable.isEmpty()
    }

    /**
     * Leaves, and waits up to [within] until this node is out of the cluster (spec 0080): removed by the others, or
     * downed, or the only member left, with nobody to hand its shards to. While it is `Leaving` its shards move to
     * their next owners. Whether it was out in time. The flock's close does this by itself, before its actors stop.
     */
    fun stop(within: Duration): Boolean {
        leave()
        return await(within) { view ->
            view.members.none { it.node == self && it.status.isLive } || view.members.all { it.node == self }
        }
    }

    /**
     * Tells [subscriber] every [MemberEvent] from now on, until it stops: first the view as it is, as each member Up
     * and each one unreachable, then each change.
     */
    fun subscribe(subscriber: ActorRef<MemberEvent>) = actor.tell(Step.Subscribe(subscriber))

    internal fun publish(next: View, loads: Balance = balance) {
        if (next == view && loads == balance) return
        val placed = next != view || loads.moved != balance.moved
        balance = loads
        // The viewers first: a region is told the view before anyone waiting on it wakes, so what a waiter tells a
        // region after its wait is routed by that view or a later one, never an earlier.
        if (placed) viewers.forEach { it(next) }
        lock.withLock {
            view = next
            changed.signalAll()
        }
    }

    /** Moves [shard] of [kind] to [to], or back to its hash owner if null, if this node leads (spec 0090). */
    internal fun move(kind: String, shard: Int, to: Node?) = actor.tell(Step.Move(kind, mapOf(shard to to)))

    /** Calls [viewer] with the view now, and with each new one after, on the cluster actor's step. */
    internal fun onView(viewer: (View) -> Unit) {
        viewers += viewer
        viewer(view)
    }

    /** Waits up to [within] for a view that [until] holds for; whether one came. */
    internal fun await(within: Duration, until: (View) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(view)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

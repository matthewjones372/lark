package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.RemoteNode
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.clock
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
}

private val StepCodec = object : MessageCodec<Step> {
    override fun write(message: Step, out: WireOut) = when (message) {
        is Step.Heard -> SwimCodec.write(message.message, out)
        Step.Tick, Step.Leave, is Step.Subscribe -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Step = Step.Heard(SwimCodec.read(input))
}

private typealias Subscribers = Set<ActorRef<MemberEvent>>

/**
 * [node] as a member of a cluster whose seeds [discovery] finds, until the flock closes. The membership runs in an
 * actor of its own, which the nodes of the cluster reach at the same path on each, and from now on it decides when
 * a watch on another node's actor ends.
 */
fun <F> Flock<F>.cluster(node: RemoteNode, discovery: Discovery, gossiping: Gossiping = Gossiping()): Cluster {
    val time = clock.get()
    fun now() = time.now().let { it.epochSecond.seconds + it.nano.nanoseconds }
    val self = Incarnation(node.self, Random.nextLong())
    val membership = Membership(self, discovery::seeds, gossiping, Random.Default, now())
    val cluster = Cluster(node.self)
    val endWatches = node.takeOverWatches()
    fun send(sends: List<Send>) = sends.forEach { (to, message) ->
        node.remote(Address(to.toString(), "/user/$CLUSTER", 0), StepCodec).tell(Step.Heard(message))
    }
    fun publish(subscribers: Subscribers) {
        val events = changes(cluster.view, membership.view())
        events.filterIsInstance<MemberEvent.Removed>().forEach { endWatches(it.member.node) }
        subscribers.forEach { subscriber -> events.forEach(subscriber::tell) }
        cluster.publish(membership.view())
    }
    val ref = spawn(
        CLUSTER,
        behaviour<Step, Subscribers>(emptySet()) { ctx, subscribers, step ->
            when (step) {
                is Step.Heard -> send(membership.receive(step.message, now()))

                Step.Tick -> send(membership.tick(now()))

                Step.Leave -> membership.leave()

                is Step.Subscribe -> {
                    ctx.watch(step.to)
                    changes(View.None, cluster.view).forEach(step.to::tell)
                    return@behaviour become(subscribers + step.to)
                }
            }
            publish(subscribers)
            stay()
        }.onStart { ctx -> ctx.timers.every(Step.Tick, gossiping.ackWithin / 2, Step.Tick) }
            .onSignal { _, subscribers, signal ->
                when (signal) {
                    is Signal.Terminated -> become(subscribers.filterTo(mutableSetOf()) { it != signal.ref })
                    Signal.Stopping -> stay()
                }
            },
    )
    node.expose(ref, StepCodec)
    cluster.actor = ref
    return cluster
}

/** One node's membership of a cluster: the view it has now, and a way to leave. */
class Cluster internal constructor(val self: Node) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var view: View = View.None
        private set

    internal lateinit var actor: ActorRef<Step>

    /** Asks to leave: the oldest member removes this one once every member has seen it go. */
    fun leave() = actor.tell(Step.Leave)

    /**
     * Tells [subscriber] every [MemberEvent] from now on, until it stops: first the view as it is, as each member Up
     * and each one unreachable, then each change.
     */
    fun subscribe(subscriber: ActorRef<MemberEvent>) = actor.tell(Step.Subscribe(subscriber))

    internal fun publish(next: View) {
        if (next == view) return
        lock.withLock {
            view = next
            changed.signalAll()
        }
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

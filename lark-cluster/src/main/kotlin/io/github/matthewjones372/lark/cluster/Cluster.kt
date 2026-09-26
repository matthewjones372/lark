package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.behaviour
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

/** What the cluster actor handles: a message from another node's, a tick of its own, or a request to leave. */
private sealed interface Step {
    data class Heard(val message: Swim) : Step

    data object Tick : Step

    data object Leave : Step
}

private val StepCodec = object : MessageCodec<Step> {
    override fun write(message: Step, out: WireOut) = when (message) {
        is Step.Heard -> SwimCodec.write(message.message, out)
        Step.Tick, Step.Leave -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Step = Step.Heard(SwimCodec.read(input))
}

/**
 * [node] as a member of a cluster whose seeds [discovery] finds, until the flock closes. The membership runs in an
 * actor of its own, which the nodes of the cluster reach at the same path on each.
 */
fun <F> Flock<F>.cluster(node: RemoteNode, discovery: Discovery, gossiping: Gossiping = Gossiping()): Cluster {
    val time = clock.get()
    fun now() = time.now().let { it.epochSecond.seconds + it.nano.nanoseconds }
    val self = Incarnation(node.self, Random.nextLong())
    val membership = Membership(self, discovery::seeds, gossiping, Random.Default, now())
    val cluster = Cluster(node.self)
    fun send(sends: List<Send>) = sends.forEach { (to, message) ->
        node.remote(Address(to.toString(), "/user/$CLUSTER", 0), StepCodec).tell(Step.Heard(message))
    }
    val ref = spawn(
        CLUSTER,
        behaviour<Step, Unit>(Unit) { _, _, step ->
            when (step) {
                is Step.Heard -> send(membership.receive(step.message, now()))
                Step.Tick -> send(membership.tick(now()))
                Step.Leave -> membership.leave()
            }
            cluster.publish(membership.view())
            stay()
        }.onStart { ctx -> ctx.timers.every(Step.Tick, gossiping.ackWithin / 2, Step.Tick) },
    )
    node.expose(ref, StepCodec)
    cluster.leaving = { ref.tell(Step.Leave) }
    return cluster
}

/** One node's membership of a cluster: the view it has now, and a way to leave. */
class Cluster internal constructor(val self: Node) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var view: View = View(emptyList(), emptySet(), null)
        private set

    internal var leaving: () -> Unit = {}

    /** Asks to leave: the oldest member removes this one once every member has seen it go. */
    fun leave() = leaving()

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

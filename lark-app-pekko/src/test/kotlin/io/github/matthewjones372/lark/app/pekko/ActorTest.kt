package io.github.matthewjones372.lark.app.pekko

import io.github.matthewjones372.lark.app.probe
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.app.validate
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

data class Add(val by: Int)

data class Greet(val who: String)

private fun counting(total: AtomicInteger, counted: CountDownLatch): Behavior<Add> =
    Behaviors.receive(Add::class.java)
        .onMessage(Add::class.java) { total.addAndGet(it.by); counted.countDown(); Behaviors.same() }
        .build()

private fun greeting(stopped: CountDownLatch): Behavior<Greet> =
    Behaviors.receive(Greet::class.java)
        .onMessage(Greet::class.java) { Behaviors.same<Greet>() }
        .onSignal(PostStop::class.java) { stopped.countDown(); Behaviors.same<Greet>() }
        .build()

class ActorTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("lark-app-pekko-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    private val provided = single<ActorSystem> { system }

    @Test
    fun `an actor is a node, and what depends on it is handed its ref`() {
        val total = AtomicInteger()
        val counted = CountDownLatch(2)
        val module = provided + actor<Add>("counter") { counting(total, counted) }

        testApp(module) { counter: ActorRef<Add> ->
            counter.tell(Add(2))
            counter.tell(Add(3))
            counted.await(5, TimeUnit.SECONDS) shouldBe true
        }

        total.get() shouldBe 5
    }

    @Test
    fun `two protocols are two nodes`() {
        val module = provided +
            actor<Add>("pair-counter") { counting(AtomicInteger(), CountDownLatch(1)) } +
            actor<Greet>("pair-greeter") { greeting(CountDownLatch(1)) }

        val plan = module.validate().getOrNull().shouldNotBeNull()

        plan.layers.flatten() shouldHaveSize 3
    }

    @Test
    fun `an actor is stopped when the graph is given back`() {
        val stopped = CountDownLatch(1)
        val module = provided + actor<Greet>("stopping") { greeting(stopped) }

        module.use { _: ActorRef<Greet> -> }.getOrNull().shouldNotBeNull()

        stopped.count shouldBe 0L
    }

    @Test
    fun `an actor that never answers its probe fails the start`() {
        val module = provided +
            actor<Greet>("silent") { greeting(CountDownLatch(1)) }
                .probe("silent", timeout = 1.seconds) { _: ActorRef<Greet> -> false }

        module.use { _: ActorRef<Greet> -> }.leftOrNull().shouldNotBeNull()
    }
}

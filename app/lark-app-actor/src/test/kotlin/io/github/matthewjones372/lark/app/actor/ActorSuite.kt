package io.github.matthewjones372.lark.app.actor

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.validate
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContainExactly
import io.github.matthewjones372.lark.junit.shouldHaveSize
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.metricTags
import io.github.matthewjones372.lark.metrics
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

sealed interface Tally

data class Add(val by: Int) : Tally

data class Total(val reply: Reply<Int>) : Tally

sealed interface Greet

data object Hello : Greet

/** Where a tally writes down what it counted; released after every actor that depends on it has stopped. */
class Ledger(val log: ConcurrentLinkedQueue<String>)

private fun tally(ledger: Ledger? = null, log: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()) =
    behaviour<Tally, Int>(0) { _, total, message ->
        when (message) {
            is Add -> {
                ledger?.log?.add("added ${message.by}")
                become(total + message.by)
            }

            is Total -> {
                message.reply(total)
                stay()
            }
        }
    }.onSignal { _, _, signal ->
        if (signal == Signal.Stopping) log += "tally stopped"
        stay()
    }

private fun greeter() = behaviour<Greet, Unit>(Unit) { _, _, _ -> stay() }

object ActorSuite : Suite() {
    init {
        test("an actor is a node, and what depends on it is handed its ref") {
            val module = actors() + actor<Tally>("tally") { tally() }

            val total = testApp(module) { tally: ActorRef<Tally> ->
                tally.tell(Add(2))
                tally.tell(Add(3))
                tally.ask(1.minutes) { Total(it) }
            }

            total shouldBe 5.right()
        }

        test("two protocols are two nodes") {
            val module = actors() + actor<Tally>("tally") { tally() } + actor<Greet>("greeter") { greeter() }

            module.validate().getOrNull().shouldNotBeNull { plan -> plan.layers.flatten() shouldHaveSize 3 }
        }

        // Each test keeps its own log: in Jupiter this was a field, fresh per test.
        test("an actor has stopped before a node it depends on is released") {
            val log = ConcurrentLinkedQueue<String>()
            val ledger = single<Ledger> { install({ Ledger(log) }) { _, _ -> log += "ledger released" } }
            val module = actors() + ledger + actor<Tally, Ledger>("tally") { found -> tally(found, log) }

            val _ = testApp(module) { tally: ActorRef<Tally> ->
                tally.tell(Add(1))
                tally.ask(1.minutes) { Total(it) }
            }

            log.toList() shouldContainExactly listOf("added 1", "tally stopped", "ledger released")
        }

        test("an actor spawned on the flock rather than as a node stops when the application does") {
            val log = ConcurrentLinkedQueue<String>()
            testApp(actors()) { actors: Actors ->
                actors.spawn("stray", tally(log = log))
                actors.awaitIdle()
            }

            log.toList() shouldContainExactly listOf("tally stopped")
        }

        test("dead letters go to the handler the node was given") {
            val letters = ConcurrentLinkedQueue<Any>()
            testApp(actors(onDeadLetter = { letters += it.message })) { actors: Actors ->
                val tally = actors.spawn("tally", tally())
                actors.stop(tally)
                tally.tell(Add(1))
            }

            letters.toList() shouldBe listOf(Add(1))
        }

        test("within, asked from the actors' own thread, runs there rather than waiting on itself") {
            val answer = testApp(actors()) { actors: Actors -> actors.within { actors.within { 42 } } }

            answer shouldBe 42
        }

        test("the actors record to the metrics, tags and logger bound where the application runs") {
            val counted = ConcurrentLinkedQueue<Pair<String, Map<String, String>>>()
            val recording = object : Metrics {
                override fun counter(name: String, tags: Map<String, String>) = Counter { counted += name to tags }

                override fun gauge(name: String, tags: Map<String, String>) = Gauge { }

                override fun histogram(name: String, tags: Map<String, String>) = Histogram { }
            }

            metrics.locally(recording) {
                metricTags.locally(mapOf("service" to "tills")) {
                    testApp(actors()) { actors: Actors ->
                        val greeter = actors.spawn("greeter", greeter())
                        actors.stop(greeter)
                        greeter.tell(Hello)
                    }
                }
            }

            counted.filter { it.first == "lark.actor.dead_letters" }.map { it.second["service"] } shouldBe
                listOf("tills")
        }
    }
}

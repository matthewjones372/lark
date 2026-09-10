package io.github.matthewjones372.lark.app.pekko

import arrow.core.Option
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

private data class Kept(val name: String)

private sealed interface Ledger

private data class Look(val name: String, val replyTo: ActorRef<Option<Kept>>) : Ledger

private fun ledger(kept: Map<String, Kept>): Behavior<Ledger> =
    Behaviors.receive(Ledger::class.java)
        .onMessage(Look::class.java) { asked ->
            asked.replyTo.tell(Option.fromNullable(kept[asked.name]))
            Behaviors.same()
        }
        .build()

/** What an actor may answer with, and what it may not. */
class AskTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("lark-app-pekko-ask-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    @TempDir
    lateinit var workspace: File

    @Test
    fun `an answer comes back on the calling thread`() {
        val kept = mapOf("anvil" to Kept("anvil"))
        val ref = org.apache.pekko.actor.typed.javadsl.Adapter.spawn(system, ledger(kept), "ledger")

        val found: Option<Kept> = ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        val missing: Option<Kept> = ref.ask(system, 3.seconds) { replyTo -> Look("nothing", replyTo) }

        found shouldBe Option.fromNullable(Kept("anvil"))
        withClue("absence is a value, because it cannot be a null message") {
            missing shouldBe Option.fromNullable(null)
        }
    }

    @Test
    fun `a nullable reply does not compile`() {
        val fixture = """
            import io.github.matthewjones372.lark.app.pekko.ask
            import org.apache.pekko.actor.ActorSystem
            import org.apache.pekko.actor.typed.ActorRef
            import kotlin.time.Duration.Companion.seconds

            data class Kept(val name: String)
            data class Look(val name: String, val replyTo: ActorRef<Kept?>)

            fun broken(ref: ActorRef<Look>, system: ActorSystem): Kept? =
                ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        """.trimIndent()

        val (exit, errors) = EmbeddedKotlin(workspace).compile(fixture)

        withClue("Pekko refuses a null message, so the reply type refuses a nullable one") {
            exit shouldBe ExitCode.COMPILATION_ERROR
        }
        withClue("Reply is bound to Any, so it is inferred as Kept and the nullable ref no longer fits") {
            errors.joinToString("\n") shouldContain "ActorRef<Kept?>"
        }
    }

    @Test
    fun `the ask fixture is the only thing stopping it`() {
        val working = """
            import arrow.core.Option
            import io.github.matthewjones372.lark.app.pekko.ask
            import org.apache.pekko.actor.ActorSystem
            import org.apache.pekko.actor.typed.ActorRef
            import kotlin.time.Duration.Companion.seconds

            data class Kept(val name: String)
            data class Look(val name: String, val replyTo: ActorRef<Option<Kept>>)

            fun works(ref: ActorRef<Look>, system: ActorSystem): Option<Kept> =
                ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        """.trimIndent()

        val (_, errors) = EmbeddedKotlin(workspace).compile(working)

        errors.shouldBeEmpty()
    }
}

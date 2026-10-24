package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.parZip
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

private data class Who(val reply: Reply<Pair<String, Int>>)

/** Spec 0180: the context bound where a request came in is the one every flag read below it answers for. */
class FeatureContextTest {

    private val client: Client = OpenFeatureAPI.getInstance().run {
        useLark()
        setProviderAndWait(Echo)
        client
    }

    @Test
    fun `a flag read inside a parZip branch answers for the key evaluatedFor bound`() {
        val (left, right) = evaluatedFor("ada") {
            parZip({ client.getStringValue("who", "nobody") }, { client.getStringValue("who", "nobody") }) { a, b ->
                a to b
            }
        }

        withClue("the SDK's own ThreadLocal propagator would leave both branches answering for nobody") {
            left shouldBe "ada"
            right shouldBe "ada"
        }
    }

    @Test
    fun `a flag read outside evaluatedFor answers for nobody`() {
        evaluatedFor("ada") { client.getStringValue("who", "nobody") } shouldBe "ada"

        client.getStringValue("who", "nobody") shouldBe "nobody"
    }

    @Test
    fun `a nested evaluatedFor adds to the context around it rather than replacing it`() {
        val (who, limit) = evaluatedFor("ada", "limit" to 3) {
            evaluatedFor("ada", "tier" to "gold") {
                client.getStringValue("who", "nobody") to client.getIntegerValue("limit", 0)
            }
        }

        who shouldBe "ada"
        withClue("the inner call's attributes merge over the outer's, so the outer limit stays") { limit shouldBe 3 }
    }

    @Test
    fun `an actor handler answers for the key and attributes the message was sent under`() {
        val handled = flock<Nothing, Pair<String, Int>> {
            val actor = spawn(
                "reader",
                behaviour<Who, Unit>(Unit) { _, _, message ->
                    stay().also {
                        message.reply(client.getStringValue("who", "nobody") to client.getIntegerValue("limit", 0))
                    }
                },
            )
            evaluatedFor("ada", "limit" to 3) { actor.ask(1.minutes) { Who(it) }.getOrNull()!! }
        }.getOrNull()

        withClue("an actor spawned outside the block sees the context only if it rides the message") {
            handled shouldBe ("ada" to 3)
        }
    }

    @Test
    fun `an attribute a Value cannot hold is refused, naming the attribute`() {
        val refused =
            shouldThrow<IllegalArgumentException> { evaluatedFor("ada", "since" to Thread.currentThread()) { } }

        refused.message shouldContain "since"
    }

    @Test
    fun `the propagator reads what was set through it`() {
        val propagator = LarkTransactionContextPropagator()
        val before = propagator.transactionContext

        propagator.transactionContext = ImmutableContext("grace")

        propagator.transactionContext.targetingKey shouldBe "grace"
        featureContext.get().targetingKey shouldBe "grace"
        propagator.transactionContext = before
    }
}

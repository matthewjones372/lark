package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.parZip
import io.github.matthewjones372.lark.resource
import kotlin.time.Duration.Companion.minutes

private data class Who(val reply: Reply<Pair<String, Int>>)

/** Spec 0180: the context bound where a request came in is the one every flag read below it answers for. */
object FeatureContextSuite : SharedSuite<Client>() {
    override val shared: Resource<Client> = resource {
        OpenFeatureAPI.getInstance().run {
            useLark()
            setProviderAndWait(Echo)
            client
        }
    }

    init {
        test("a flag read inside a parZip branch answers for the key evaluatedFor bound") { client ->
            val (left, right) = evaluatedFor("ada") {
                parZip({ client.getStringValue("who", "nobody") }, { client.getStringValue("who", "nobody") }) { a, b ->
                    a to b
                }
            }

            withClue("the SDK's own ThreadLocal propagator would leave both branches answering for nobody") {
                (left shouldBe "ada") and (right shouldBe "ada")
            }
        }

        test("a flag read outside evaluatedFor answers for nobody") { client ->
            (evaluatedFor("ada") { client.getStringValue("who", "nobody") } shouldBe "ada") and
                (client.getStringValue("who", "nobody") shouldBe "nobody")
        }

        test("a nested evaluatedFor adds to the context around it rather than replacing it") { client ->
            val (who, limit) = evaluatedFor("ada", "limit" to 3) {
                evaluatedFor("ada", "tier" to "gold") {
                    client.getStringValue("who", "nobody") to client.getIntegerValue("limit", 0)
                }
            }

            (who shouldBe "ada") and
                withClue("the inner call's attributes merge over the outer's, so the outer limit stays") {
                    limit shouldBe 3
                }
        }

        test("an actor handler answers for the key and attributes the message was sent under") { client ->
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

        test("an attribute a Value cannot hold is refused, naming the attribute") { _ ->
            val since = "since" to Thread.currentThread()
            shouldThrow<IllegalArgumentException>({ evaluatedFor("ada", since) { } }) { refused ->
                refused.message shouldContain "since"
            }
        }

        test("the propagator reads what was set through it") { _ ->
            val propagator = LarkTransactionContextPropagator()
            val before = propagator.transactionContext

            propagator.transactionContext = ImmutableContext("grace")

            val throughIt = propagator.transactionContext.targetingKey shouldBe "grace"
            val bound = featureContext.get().targetingKey shouldBe "grace"
            propagator.transactionContext = before
            throughIt and bound
        }
    }
}

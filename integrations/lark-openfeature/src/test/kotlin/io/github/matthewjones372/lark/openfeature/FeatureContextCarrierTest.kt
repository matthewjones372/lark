package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.Value
import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What crosses a message: the targeting key and every scalar attribute, each as the type it was. */
class FeatureContextCarrierTest {

    private val carrier = FeatureContextCarrier()

    private fun across(context: ImmutableContext) =
        featureContext.locally(context) { carrier.capture() }.let { carried ->
            carrier.within(carried) { featureContext.get() }
        }

    @Test
    fun `nothing is carried when no context is bound`() {
        carrier.capture().shouldBeEmpty()
    }

    @Test
    fun `a string, an int, a long, a double and a boolean arrive as what they were`() {
        val sent = ImmutableContext(
            "ada",
            mapOf(
                "tier" to Value("gold"),
                "limit" to Value(3),
                "since" to Value(1_700_000_000_000L),
                "ratio" to Value(0.5),
                "beta" to Value(true),
            ),
        )

        val arrived = across(sent)

        arrived.targetingKey shouldBe "ada"
        arrived.getValue("tier").asObject() shouldBe "gold"
        arrived.getValue("limit").asObject() shouldBe 3
        arrived.getValue("since").asObject() shouldBe 1_700_000_000_000L
        arrived.getValue("ratio").asObject() shouldBe 0.5
        arrived.getValue("beta").asObject() shouldBe true
    }

    @Test
    fun `a structure or a list is left behind, and the scalars beside it still cross`() {
        val sent = ImmutableContext(
            "ada",
            mapOf(
                "address" to Value(ImmutableStructure(mapOf("city" to Value("Paris")))),
                "roles" to Value(listOf(Value("admin"))),
                "tier" to Value("gold"),
            ),
        )

        val arrived = across(sent)

        withClue("only scalars have a string form both ends agree on") {
            arrived.keySet() shouldBe setOf("targetingKey", "tier")
        }
    }

    @Test
    fun `keys that are not this carrier's are left alone`() {
        carrier.within(mapOf("traceparent" to "00-abc")) { featureContext.get().isEmpty } shouldBe true
    }
}

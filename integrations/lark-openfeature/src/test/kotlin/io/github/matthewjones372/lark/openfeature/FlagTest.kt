package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.parZip
import org.junit.jupiter.api.Test
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

private object TestFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
    val greeting = Flag.string("adoption.greeting", default = "hello")
    val discount = Flag.double("adoption.discount", default = 0.0)
    val missing = Flag.int("adoption.nobody-declared-this", default = 7)
    val wrongType = Flag.boolean("adoption.per-customer-limit", default = false)
}

/** Spec 0181: a flag declared once, read with no client passed in. */
class FlagTest {

    private fun <A> one(value: A): MemoryFlag<A> =
        MemoryFlag.builder<A>().variant("on", value).defaultVariant("on").build()

    private val client = OpenFeatureAPI.getInstance().run {
        useLark()
        setProviderAndWait(
            "flags",
            InMemoryProvider(
                mapOf(
                    "adoption.registry-check" to one(false),
                    "adoption.per-customer-limit" to one(5),
                    "adoption.greeting" to one("welcome"),
                    "adoption.discount" to one(0.25),
                ),
            ),
        )
        getClient("flags")
    }

    @Test
    fun `each factory reads its flag as the type it declares`() {
        featureClient.locally(client) {
            TestFlags.registryCheck.value() shouldBe false
            TestFlags.perAdopterLimit.value() shouldBe 5
            TestFlags.greeting.value() shouldBe "welcome"
            TestFlags.discount.value() shouldBe 0.25
        }
    }

    @Test
    fun `a flag no provider knows answers its default`() {
        featureClient.locally(client) {
            TestFlags.missing.value() shouldBe 7
            TestFlags.missing.details().errorCode shouldBe ErrorCode.FLAG_NOT_FOUND
        }
    }

    @Test
    fun `a flag read as the wrong type answers its default, with TYPE_MISMATCH`() {
        featureClient.locally(client) {
            val details = TestFlags.wrongType.details()

            details.value shouldBe false
            details.errorCode shouldBe ErrorCode.TYPE_MISMATCH
        }
    }

    @Test
    fun `a fork reads the client its opener bound`() {
        val (left, right) = featureClient.locally(client) {
            parZip({ TestFlags.perAdopterLimit.value() }, { TestFlags.greeting.value() }) { a, b -> a to b }
        }

        withClue("the default client has no provider for these flags, so a fork that lost the binding reads 3") {
            left shouldBe 5
            right shouldBe "welcome"
        }
    }

    @Test
    fun `with nothing bound, a flag reads the default client`() {
        withClue("the default client belongs to no domain") { featureClient.get().metadata.domain shouldBe null }
        TestFlags.missing.key shouldBe "adoption.nobody-declared-this"
        TestFlags.missing.default shouldBe 7
    }
}

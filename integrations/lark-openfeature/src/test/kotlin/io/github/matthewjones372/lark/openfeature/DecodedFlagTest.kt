package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.capturingMetrics
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

private enum class Checkout { Classic, Express }

private data class Window(val from: Int, val to: Int)

private fun window(value: Value): Window =
    value.asStructure().let { Window(it.getValue("from").asInteger(), it.getValue("to").asInteger()) }

private object Decoded {
    val limit = Flag.long("decoded.limit", default = 1L)
    val timeout = Flag.duration("decoded.timeout", default = 2.seconds)
    val badTimeout = Flag.duration("decoded.bad-timeout", default = 2.seconds)
    val cutOver = Flag.instant("decoded.cut-over", default = Instant.MAX)
    val checkout = Flag.enum("decoded.checkout", default = Checkout.Classic)
    val badCheckout = Flag.enum("decoded.bad-checkout", default = Checkout.Classic)
    val window = Flag.of("decoded.window", default = Window(0, 24), ::window)
    val missingWindow = Flag.of("decoded.no-window", default = Window(0, 24), ::window)
}

/** Spec 0183: a flag read as the type the code wants, decoded once beside its key and default. */
class DecodedFlagTest {

    private fun <A> one(value: A): MemoryFlag<A> =
        MemoryFlag.builder<A>().variant("on", value).defaultVariant("on").build()

    private val client = OpenFeatureAPI.getInstance().run {
        useLark()
        setProviderAndWait(
            "decoded",
            InMemoryProvider(
                mapOf(
                    "decoded.limit" to one(5_000_000_000L),
                    "decoded.timeout" to one("5s"),
                    "decoded.bad-timeout" to one("soon"),
                    "decoded.cut-over" to one("2026-01-01T00:00:00Z"),
                    "decoded.checkout" to one("Express"),
                    "decoded.bad-checkout" to one("express"),
                    "decoded.window" to one(
                        Value(ImmutableStructure(mapOf("from" to Value(9), "to" to Value(17)))),
                    ),
                ),
            ),
        )
        getClient("decoded")
    }

    private fun <A> read(block: () -> A): A = featureClient.locally(client, block)

    @Test
    fun `each factory decodes a good value into the type it declares`() {
        read {
            Decoded.limit.value() shouldBe 5_000_000_000L
            Decoded.timeout.value() shouldBe 5.seconds
            Decoded.cutOver.value() shouldBe Instant.parse("2026-01-01T00:00:00Z")
            Decoded.checkout.value() shouldBe Checkout.Express
            Decoded.window.value() shouldBe Window(9, 17)
        }
    }

    @Test
    fun `a value that will not decode answers the default, with PARSE_ERROR, one warning and one error counted`() {
        val (details, warnings) = capturingLogs { logs ->
            capturingMetrics { measured ->
                val details = read { Decoded.badTimeout.details() }
                withClue("counted once, under the flag and the code") {
                    measured.counter("openfeature.errors") shouldBe 1.0
                    measured.tags("openfeature.errors") shouldBe
                        mapOf("flag" to "decoded.bad-timeout", "code" to "PARSE_ERROR")
                }
                details
            } to logs.all().filter { it.level == LogLevel.Warn }
        }

        details.value shouldBe 2.seconds
        details.errorCode shouldBe ErrorCode.PARSE_ERROR
        details.errorMessage.orEmpty() shouldContain "soon"
        warnings.size shouldBe 1
        warnings.single().message shouldContain "decoded.bad-timeout"
    }

    @Test
    fun `an enum matches its constant's name exactly, so a wrong case is a PARSE_ERROR`() {
        val details = read { Decoded.badCheckout.details() }

        details.value shouldBe Checkout.Classic
        details.errorCode shouldBe ErrorCode.PARSE_ERROR
    }

    @Test
    fun `a flag no provider knows answers its default with the SDK's own code, and is not decoded`() {
        val details = read { Decoded.missingWindow.details() }

        details.value shouldBe Window(0, 24)
        details.errorCode shouldBe ErrorCode.FLAG_NOT_FOUND
    }
}

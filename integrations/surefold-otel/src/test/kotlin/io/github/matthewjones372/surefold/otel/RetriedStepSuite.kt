package io.github.matthewjones372.surefold.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.transact
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

private const val RETRIES = 3

// Commits a change to [shared] from another thread, so the run that read it cannot commit.
private fun <T> changeElsewhere(shared: Shared<T>, change: (T) -> T) =
    Thread.ofVirtual().start { transact { shared.update(change) } }.join()

private fun tracerExportingTo(exported: Collected): Tracer = OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build())
    .build()
    .getTracer("surefold-otel-test")

/** Spec 0212: a step that had to run again says so on the span it ran in, once, when it commits. */
object RetriedStepSuite : Suite() {
    init {

        test("a step that ran again three times adds one event with four attempts and its causes") {
            val exported = Collected()
            val tracer = tracerExportingTo(exported)
            val stalls = Shared(10, name = "free.stalls")
            var runs = 0

            tracer.span("checkout") {
                transact("hold") {
                    val left = stalls.get()
                    if (++runs <= RETRIES) changeElsewhere(stalls) { it + 1 }
                    stalls.set(left - 1)
                }
            }

            val event = exported.all().single { it.name == "checkout" }.events.single()
            event.name shouldBe "transact.retried"
            event.attributes.get(AttributeKey.stringKey("transact")) shouldBe "hold"
            event.attributes.get(AttributeKey.longKey("attempts")) shouldBe 4L
            event.attributes.get(AttributeKey.longKey("retries.free.stalls")) shouldBe 3L
        }

        test("a step that commits on its first run adds nothing to the span") {
            val exported = Collected()
            val tracer = tracerExportingTo(exported)
            val free = Shared(10)

            tracer.span("checkout") { transact("hold") { free.update { it - 1 } } }

            exported.all().single { it.name == "checkout" }.events shouldBe emptyList()
        }
    }
}

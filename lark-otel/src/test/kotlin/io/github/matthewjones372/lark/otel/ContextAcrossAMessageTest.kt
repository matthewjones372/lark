package io.github.matthewjones372.lark.otel

import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

private data class Handle(val reply: Reply<String>)

/** Spec 0122: the trace a message was sent in is the one its handler runs in. */
class ContextAcrossAMessageTest {

    private val exported = Collected()

    private val tracer = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build())
        .build()
        .getTracer("lark-otel-test")

    private fun handler() = behaviour<Handle, Unit>(Unit) { _, _, message ->
        stay().also { message.reply(tracer.span("handled") { Span.current().spanContext.traceId }) }
    }

    @Test
    fun `a span open at an ask is the parent of the span its handler opens`() {
        flock<Nothing, String> {
            val actor = spawn("handler", handler())
            tracer.span("asking") { actor.ask(1.minutes) { Handle(it) }.getOrNull()!! }
        }

        val spans = exported.all()
        val asking = spans.single { it.name == "asking" }
        val handled = spans.single { it.name == "handled" }
        withClue("without the carrier, the handler's span is a root of its own") {
            handled.traceId shouldBe asking.traceId
            handled.parentSpanId shouldBe asking.spanId
        }
    }

    @Test
    fun `a message sent outside any span is handled outside one`() {
        flock<Nothing, String> { spawn("handler", handler()).ask(1.minutes) { Handle(it) }.getOrNull()!! }

        exported.all().single { it.name == "handled" }.parentSpanId shouldBe "0000000000000000"
    }
}

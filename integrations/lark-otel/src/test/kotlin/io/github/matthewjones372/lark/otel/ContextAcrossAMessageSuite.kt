package io.github.matthewjones372.lark.otel

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.time.Duration.Companion.minutes

private data class Handle(val reply: Reply<String>)

/** A tracer and the spans it finished, made fresh for each test so one test never reads another's. */
private class Traced {
    val exported = Collected()

    val tracer: Tracer = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build())
        .build()
        .getTracer("lark-otel-test")

    fun handler() = behaviour<Handle, Unit>(Unit) { _, _, message ->
        stay().also { message.reply(tracer.span("handled") { Span.current().spanContext.traceId }) }
    }
}

/** Spec 0122: the trace a message was sent in is the one its handler runs in. */
object ContextAcrossAMessageSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a span open at an ask is the parent of the span its handler opens") { _ ->
            val traced = Traced()
            val _ = flock<Nothing, String> {
                val actor = spawn("handler", traced.handler())
                traced.tracer.span("asking") { actor.ask(1.minutes) { Handle(it) }.getOrNull()!! }
            }

            val spans = traced.exported.all()
            val asking = spans.single { it.name == "asking" }
            val handled = spans.single { it.name == "handled" }
            withClue("without the carrier, the handler's span is a root of its own") {
                (handled.traceId shouldBe asking.traceId) and (handled.parentSpanId shouldBe asking.spanId)
            }
        }

        test("a message sent outside any span is handled outside one") { _ ->
            val traced = Traced()
            val _ = flock<Nothing, String> {
                spawn("handler", traced.handler()).ask(1.minutes) { Handle(it) }.getOrNull()!!
            }

            traced.exported.all().single { it.name == "handled" }.parentSpanId shouldBe "0000000000000000"
        }
    }
}

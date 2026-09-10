package io.github.matthewjones372.lark.otel

import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextStorage
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test

/** What a trace does when the work forks, which is the thing a ThreadLocal cannot answer. */
class ContextAcrossAForkTest {

    private val exported = Collected()

    private val tracing = OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build(),
        )
        .build()

    private val tracer = tracing.getTracer("lark-otel-test")

    @Test
    fun `the storage OpenTelemetry found is lark's`() {
        withClue("META-INF/services must name LarkContextStorageProvider") {
            ContextStorage.get()::class.java.name shouldBe LarkContextStorage::class.java.name
        }
    }

    @Test
    fun `a context bound outside a parMap is current inside every branch`() {
        val outside = Context.root().with(tracer.spanBuilder("outer").startSpan())

        val seen = otelContext.locally(outside) { parMap(listOf(1, 2, 3)) { Context.current() } }

        seen shouldHaveSize 3
        seen.forEach { branch -> branch shouldBe outside }
    }

    @Test
    fun `a span opened before a fork is the parent of what each branch opens`() {
        tracer.span("outer") {
            parMap(listOf(1, 2)) { tracer.span("branch-$it") { Span.current().spanContext.spanId } }
        }

        val spans = exported.all()
        val outer = spans.single { it.name == "outer" }
        val branches = spans.filter { it.name.startsWith("branch-") }

        branches shouldHaveSize 2
        withClue("a ThreadLocal would have made each branch a root of its own") {
            branches.forEach { branch -> branch.parentSpanId shouldBe outer.spanId }
        }
    }

    @Test
    fun `attach puts back what it replaced`() {
        val first = Context.root().with(tracer.spanBuilder("first").startSpan())
        val before = Context.current()

        val detach = otelContext.attach(first)
        Context.current() shouldBe first
        detach.detach()

        Context.current() shouldBe before
    }

    @Test
    fun `a traced span puts the trace on every line, including a branch's`() {
        val lines = capturingLogs { logs ->
            tracer.tracedSpan("register") { parMap(listOf(1, 2)) { logInfo("branch $it") } }
            logs.all()
        }

        lines shouldHaveSize 2
        lines.forEach { line ->
            line.annotations["trace_id"].orEmpty().shouldNotBeBlank()
            line.annotations["span_id"].orEmpty().shouldNotBeBlank()
        }
    }
}

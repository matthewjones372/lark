package io.github.matthewjones372.lark.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections

/** The spans a test finished, kept where the test can read them. */
class Collected : SpanExporter {

    private val spans: MutableList<SpanData> = Collections.synchronizedList(mutableListOf())

    fun all(): List<SpanData> = synchronized(spans) { spans.toList() }

    override fun export(exported: Collection<SpanData>): CompletableResultCode {
        spans += exported
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

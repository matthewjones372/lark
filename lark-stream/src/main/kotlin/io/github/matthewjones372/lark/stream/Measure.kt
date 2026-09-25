package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.increment
import java.util.concurrent.atomic.AtomicLong

// A duration is recorded in milliseconds, the unit lark's own timers use and a dashboard is read in.
private const val NANOS_PER_MILLI = 1_000_000.0

/** What a stage of a pipeline sampled one element in 64 of, by default: a clock read costs about what fusing saves. */
private const val SAMPLE_EVERY = 64

/**
 * Where a run's numbers go, and what they are filed under. Each stage reports `lark.stream.elements`,
 * the elements it emitted, and `lark.stream.busy`, the time inside its own body for one element in
 * [sampleEvery], tagged `pipeline`, `stage`, `at` and `step`, its place in the order data moves.
 */
class Measured(
    val pipeline: String,
    val metrics: Metrics = io.github.matthewjones372.lark.metrics.get(),
    val sampleEvery: Int = SAMPLE_EVERY,
) {
    init {
        require(sampleEvery > 0) { "sampleEvery must be at least 1, and was $sampleEvery" }
    }
}

/**
 * This run with every stage measured. Built once and started as often as needed, as any run is: the
 * instruments are looked up here, not per element. An unmeasured run is untouched and pays nothing.
 */
fun <E, R> Run<E, R>.measured(by: Measured): Run<E, R> = Run(node.measuredBy(by), end)

/** [measured], then [start]: for a run started once. */
fun <E, R : Any> Run<E, R>.start(backend: StreamBackend, measured: Measured): Running<E, R> =
    measured(measured).start(backend)

@StreamSpi
fun Node.measuredBy(by: Measured): Node {
    val order = dataOrder()
    return measured(by) { node -> order.indexOfFirst { it === node } }
}

/**
 * Every node once, each after the nodes it reads from: the order data moves, and the order a rendering
 * and a measurement number the stages in.
 */
@StreamSpi
fun Node.dataOrder(): List<Node> =
    children().fold(emptyList<Node>()) { seen, child ->
        seen + child.dataOrder().filter { n -> seen.none { it === n } }
    }.let { before -> if (before.any { it === this }) before else before + this }

/** One stage's instruments. */
private class Instruments(by: Measured, node: Node, step: Int) {
    private val tags = mapOf(
        "pipeline" to by.pipeline,
        "stage" to node.operator,
        "at" to (node.site ?: "-"),
        "step" to step.toString(),
    )
    val elements: Counter = by.metrics.counter("lark.stream.elements", tags)

    // Looked up on the first sample, so a stage with no body to time reports no `busy` at all rather than
    // an empty series a dashboard would draw as a stage that took no time.
    private val busy: Histogram by lazy { by.metrics.histogram("lark.stream.busy", tags) }
    private val every = by.sampleEvery.toLong()
    private val seen = AtomicLong()

    /** [body], timed when this element is the one in [every] that is sampled. */
    fun <T> timed(body: () -> T): T {
        if (seen.incrementAndGet() % every != 0L) return body()
        val started = System.nanoTime()
        try {
            return body()
        } finally {
            busy.record((System.nanoTime() - started) / NANOS_PER_MILLI)
        }
    }
}

/**
 * The tree with each stage's body timed and its elements counted. The four operators fusing merges count
 * inside their own bodies, so a measured run fuses as an unmeasured one does; every other stage is
 * followed by a [Node.Counted] that counts what it emitted.
 */
private fun Node.measured(by: Measured, step: (Node) -> Int): Node {
    val stage = Instruments(by, this, step(this))
    return when (this) {
        is Node.Map -> Node.Map(
            upstream.measured(by, step),
            { a -> stage.timed { f(a) }.also { stage.elements.increment() } },
            at,
        )

        is Node.MapOrFail -> Node.MapOrFail(
            upstream.measured(by, step),
            { a -> stage.timed { f(a) }.also { stage.elements.increment() } },
            at,
        )

        is Node.Filter -> Node.Filter(upstream.measured(by, step), stage.keeping(predicate, keep = true), at)

        is Node.FilterNot -> Node.FilterNot(upstream.measured(by, step), stage.keeping(predicate, keep = false), at)

        is Node.MapConcat -> Node.Counted(
            Node.MapConcat(upstream.measured(by, step), { a -> stage.timed { f(a) } }, at),
            stage.elements,
        )

        is Node.MapPar -> Node.Counted(
            Node.MapPar(upstream.measured(by, step), parallelism, on, { a -> stage.timed { f(a) } }, at),
            stage.elements,
        )

        Node.Hole -> this

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail,
        Node.Empty, is Node.Merge, is Node.Interleave, is Node.ZipWith, is Node.Prepend, is Node.Concat,
        is Node.RestartOnDefect, is Node.Stage, is Node.Take, is Node.Drop, is Node.TakeWhile, is Node.DropWhile,
        is Node.Grouped, is Node.Sliding, is Node.GroupedWithin, is Node.Scan, is Node.StatefulMap,
        is Node.MapAsync, is Node.Conflate, is Node.Either, is Node.Absolve, is Node.CatchAll, is Node.MapError,
        is Node.OrFailIfEmpty, is Node.FlatMap, is Node.Fused, is Node.Counted,
        -> Node.Counted(withChildren { it.measured(by, step) }, stage.elements)
    }
}

private fun Instruments.keeping(predicate: (Any) -> Boolean, keep: Boolean): (Any) -> Boolean =
    { a -> timed { predicate(a) }.also { kept -> if (kept == keep) elements.increment() } }

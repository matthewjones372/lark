package io.github.matthewjones372.lark.stream

/** How [render] lays a pipeline out: [Text] for a log line or a test failure, [Mermaid] for a document. */
sealed interface Layout {
    data object Text : Layout

    data object Mermaid : Layout
}

/**
 * The pipeline as a reader would draw it, in the order data moves: each operator by the name it was
 * written with, its plain arguments, and the line that wrote it. [optimised] shows what a backend
 * compiles instead: after `collapsed` and `fused`.
 */
fun Stream<*, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false): String =
    rendered(node.maybeOptimised(optimised), end = null, layout)

fun Pipe<*, *, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false): String =
    rendered(node.maybeOptimised(optimised), end = null, layout)

/** As the stream's, with the end the run completes through as its last step. */
fun Run<*, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false): String =
    rendered(node.maybeOptimised(optimised), end, layout)

private fun Node.maybeOptimised(optimised: Boolean): Node = if (optimised) optimised() else this

private fun rendered(node: Node, end: End?, layout: Layout): String =
    when (layout) {
        Layout.Text -> text(node, end)
        Layout.Mermaid -> mermaid(node, end)
    }

/** One line of the text layout: the tree drawing and label on the left, the caller's line on the right. */
private class Row(val label: String, val site: String?)

private fun text(node: Node, end: End?): String {
    val rows = node.rows() + listOfNotNull(end?.row())
    val width = rows.maxOf { it.label.length } + 2
    return rows.joinToString("\n") { row -> row.site?.let { row.label.padEnd(width) + it } ?: row.label }
}

/**
 * A straight run of operators is a flat list, source first. An operator that reads more than one stream
 * heads a branch for each, drawn beneath it, in the order its elements come from them.
 */
private fun Node.rows(): List<Row> {
    val inputs = children()
    val here = Row(label(), site)
    return if (inputs.size < 2) {
        inputs.flatMap { it.rows() } + here
    } else {
        listOf(Row("${here.label} of", here.site)) +
            inputs.flatMapIndexed { index, input -> input.rows().branch(last = index == inputs.lastIndex) }
    }
}

private fun List<Row>.branch(last: Boolean): List<Row> =
    mapIndexed { index, row ->
        val lead = when {
            index == 0 && last -> "└ "
            index == 0 -> "├ "
            last -> "  "
            else -> "│ "
        }
        Row(lead + row.label, row.site)
    }

private fun End.row(): Row =
    when (this) {
        End.Collect -> Row("runCollect", null)
        is End.Fold -> Row("runFold", at)
        is End.Native -> Row(builder, at)
    }

private fun mermaid(node: Node, end: End?): String {
    val nodes = node.inDataOrder()
    val id = { of: Node -> "n${nodes.indexOfFirst { it === of }}" }
    val declared = nodes.map { "    ${id(it)}[\"${quoted(it.label(), it.site)}\"]" }
    val edges = nodes.flatMap { to -> to.children().map { from -> "    ${id(from)} --> ${id(to)}" } }
    // `end` is a Mermaid keyword, so the run's last step is `run`.
    val ending = end?.row()?.let { row ->
        listOf("    run[\"${quoted(row.label, row.site)}\"]", "    ${id(node)} --> run")
    }.orEmpty()
    return (listOf("flowchart TD") + declared + edges + ending).joinToString("\n")
}

/** Every node once, each after the nodes it reads from, so the ids read in the order data moves. */
private fun Node.inDataOrder(): List<Node> =
    children().fold(emptyList<Node>()) { seen, child ->
        seen +
            child.inDataOrder().filter { n -> seen.none { it === n } }
    }
        .let { before -> if (before.any { it === this }) before else before + this }

/** A Mermaid label in double quotes: a quote inside it is Mermaid's own entity, not the end of the label. */
private fun quoted(label: String, site: String?): String =
    (label + site?.let { " · $it" }.orEmpty()).replace("\"", "#quot;")

/** The operator and the arguments a reader needs to tell one of it from another. A lambda says nothing. */
private fun Node.label(): String {
    val arguments = when (this) {
        is Node.Take -> "$n"

        is Node.Drop -> "$n"

        is Node.Grouped -> "$n"

        is Node.Sliding -> "$n, $step"

        is Node.GroupedWithin -> "$n, $within"

        is Node.Tick -> "$every"

        is Node.MapPar -> "$parallelism"

        is Node.MapAsync -> "$parallelism"

        is Node.Interleave -> "$segmentSize"

        is Node.FlatMap -> breadth?.toString()

        is Node.Native, is Node.Stage, is Node.Elements, is Node.Single, is Node.FromStage, is Node.Fail,
        Node.Empty, Node.Hole, is Node.Merge, is Node.ZipWith, is Node.Prepend, is Node.Concat,
        is Node.RestartOnDefect, is Node.Map, is Node.MapOrFail, is Node.Filter, is Node.FilterNot,
        is Node.TakeWhile, is Node.DropWhile, is Node.Scan, is Node.StatefulMap, is Node.MapConcat,
        is Node.Conflate, is Node.Either, is Node.Absolve, is Node.CatchAll, is Node.MapError,
        is Node.OrFailIfEmpty, is Node.Fused,
        -> null
    }
    return operator + arguments?.let { "($it)" }.orEmpty()
}

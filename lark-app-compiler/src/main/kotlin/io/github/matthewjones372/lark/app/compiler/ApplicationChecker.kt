package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** The class every application extends, which is how one is recognised without an annotation. */
private val larkApp = ClassId(FqName("io.github.matthewjones372.lark.app"), Name.identifier("LarkApp"))

private val module = Name.identifier("module")

/**
 * Every `LarkApp` in what is being compiled, and what its `module` was written as.
 *
 * A graph this cannot read is left alone rather than guessed at, so an application assembled in a
 * way the reader does not know is silent here and still checked by `larkWiring`, which runs it.
 */
internal class ApplicationChecker(private val verbose: Boolean) : FirClassChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (!declaration.isApplication()) return

        // Nothing is read in a compiler this was not built for. What it would find there is not
        // wrong so much as unknown, and an unknown answer reported as an error is the one outcome
        // this checker must never have.
        if (!builtForThisCompiler()) {
            reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_UNREAD, versionMismatch(), context)
            return
        }

        val gave = Gave()
        val graph = declaration.graph(gave)

        if (graph == null) {
            val why = gave.at ?: "a shape it does not know"
            reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_UNREAD, why, context)
        }

        val unreached = graph?.unreachedFrom(declaration).orEmpty()

        declaration.faults(graph, unreached)

        if (verbose) {
            val read = declaration.read(graph, gave, unreached)
            reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_FOUND, read, context)
        }
    }

    /** Every fault the graph holds, each on the line a reader would edit to be rid of it. */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun FirClass.faults(graph: Graph?, unreached: List<String>) {
        graph?.missing().orEmpty().forEach { need ->
            // On the recipe that asked rather than on the application: the line to edit is the one
            // the dependency was written on, which is the line `larkWiring` names too.
            reporter.reportOn(need.at ?: source, LarkDiagnostics.LARK_APP_MISSING, need.said(), context)
        }

        graph?.shadows.orEmpty().forEach { shadow ->
            // On the one that wins, which is `Diagnostics.kt`'s own choice: it is the line a reader
            // edits to stop the other being shadowed.
            reporter.reportOn(shadow.wins ?: source, LarkDiagnostics.LARK_APP_DUPLICATE, twiceSaid(shadow), context)
        }

        unreached.forEach { key ->
            // On the recipe that built it, which is the line to delete, and the line larkWiring
            // names for the same node.
            val at = graph?.provides?.get(key) ?: source
            reporter.reportOn(at, LarkDiagnostics.LARK_APP_UNREACHABLE, unreachedSaid(key), context)
        }
    }

    /**
     * What the root reaches nothing of, or nothing at all where the root is not a key this builds.
     *
     * A root the graph does not provide would leave every node unreached, which is the one wrong
     * answer this must never give: `larkWiring` fails that build on the root itself.
     */
    private fun Graph.unreachedFrom(declaration: FirClass): List<String> =
        declaration.root()?.takeIf { it in provides }?.let { unreached(it) }.orEmpty()

    /**
     * The root, read off the type argument the subclass wrote.
     *
     * The same place `LarkApp.root` reads it from — a type argument on a subclass declaration is
     * kept, where one at a use site is erased.
     */
    private fun FirClass.root(): String? =
        superTypeRefs.firstOrNull { it.coneType.classId == larkApp }
            ?.coneType
            ?.typeArguments
            ?.firstOrNull()
            ?.let { (it as? ConeKotlinTypeProjection)?.type }
            ?.let(::keyOf)

    /** The sentence `lark-app` prints for the same fault, so a reader meets one wording not two. */
    private fun Need.said(): String = "${labelOf(by)} needs ${labelOf(key)}, and nothing builds it"

    private fun FirClass.read(graph: Graph?, gave: Gave, unreached: List<String>): String = when (graph) {
        null -> "${name()} was not read: gave up at ${gave.at}"

        else -> "${name()} provides ${graph.provides.keys.map(::labelOf).sorted()}" +
            ", and is short of ${graph.missing().map { labelOf(it.key) }.distinct().sorted()}" +
            read(unreached)
    }

    /** Silent where everything is reached, so a working graph's line stays the one it was. */
    private fun read(unreached: List<String>): String =
        if (unreached.isEmpty()) "" else ", and nothing reaches ${unreached.map(::labelOf)}"

    /** `Diagnostics.kt`'s sentence, down to the `another` it falls back on when it has no site. */
    context(context: CheckerContext)
    private fun twiceSaid(shadow: Shadow): String =
        "${labelOf(shadow.key)} is provided twice; this one wins over ${shortly(shadow.shadowed)}"

    /**
     * A site as `Wiring.kt:92`, which is what `lark-app`'s own `shortly` renders.
     *
     * Every site a graph holds has been anchored into the file being checked, so the name is this
     * file's and only the line has to be worked out. A compiler that will not say which line — the
     * mapping is nullable — falls back to the word the runtime report uses for a site it lacks.
     */
    @OptIn(SymbolInternals::class)
    context(context: CheckerContext)
    private fun shortly(at: KtSourceElement?): String {
        val file = context.containingFileSymbol?.fir ?: return "another"
        val line = at?.startOffset?.let { file.sourceFileLinesMapping?.getLineByOffset(it) } ?: return "another"
        val name = file.sourceFile?.name?.substringAfterLast('/') ?: return "another"
        return "$name:${line + 1}"
    }

    /** The sentence `lark-app` prints for the same node, so a reader meets one wording not two. */
    private fun unreachedSaid(key: String): String =
        "nothing reaches ${labelOf(key)}, and it is built on every start"

    private fun FirClass.isApplication(): Boolean = superTypeRefs.any { it.coneType.classId == larkApp }

    @OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)
    context(context: CheckerContext)
    private fun FirClass.graph(gave: Gave): Graph? =
        declarations.filterIsInstance<FirProperty>()
            .firstOrNull { it.name == module }
            ?.initializer
            ?.let { read(it, gave, Here.of(context.session, context.containingFileSymbol?.fir)) }

    private fun FirClass.name(): String = symbol.classId.asFqNameString()
}

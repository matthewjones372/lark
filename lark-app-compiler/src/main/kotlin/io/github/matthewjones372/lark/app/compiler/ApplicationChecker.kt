package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
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
        val gave = Gave()
        val graph = declaration.graph(gave)

        graph?.missing().orEmpty().forEach { need ->
            // On the recipe that asked rather than on the application: the line to edit is the one
            // the dependency was written on, which is the line `larkWiring` names too.
            reporter.reportOn(need.at ?: declaration.source, LarkDiagnostics.LARK_APP_MISSING, need.said(), context)
        }

        if (verbose) {
            val read = declaration.read(graph, gave)
            reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_FOUND, read, context)
        }
    }

    /** The sentence `lark-app` prints for the same fault, so a reader meets one wording not two. */
    private fun Need.said(): String = "${labelOf(by)} needs ${labelOf(key)}, and nothing builds it"

    private fun FirClass.read(graph: Graph?, gave: Gave): String = when (graph) {
        null -> "${name()} was not read: gave up at ${gave.at}"

        else -> "${name()} provides ${graph.provides.map(::labelOf).sorted()}" +
            ", and is short of ${graph.missing().map { labelOf(it.key) }.distinct().sorted()}"
    }

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

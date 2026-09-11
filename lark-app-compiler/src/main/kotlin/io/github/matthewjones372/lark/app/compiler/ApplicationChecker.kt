package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirProperty
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

        if (!verbose) return
        val said = when (graph) {
            null -> "${declaration.name()} was not read: gave up at ${gave.at}"

            else -> "${declaration.name()} provides ${graph.provides.map(::labelOf).sorted()}" +
                ", and is short of ${graph.missing().map { labelOf(it.key) }.distinct().sorted()}"
        }
        reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_FOUND, said, context)
    }

    private fun FirClass.isApplication(): Boolean = superTypeRefs.any { it.coneType.classId == larkApp }

    @OptIn(org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class)
    private fun FirClass.graph(gave: Gave): Graph? =
        declarations.filterIsInstance<FirProperty>()
            .firstOrNull { it.name == module }
            ?.initializer
            ?.let { read(it, gave) }

    private fun FirClass.name(): String = symbol.classId.asFqNameString()
}

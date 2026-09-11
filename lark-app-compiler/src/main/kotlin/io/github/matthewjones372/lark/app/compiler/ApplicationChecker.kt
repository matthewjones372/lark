package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** The class every application extends, which is how one is recognised without an annotation. */
private val larkApp = ClassId(FqName("io.github.matthewjones372.lark.app"), Name.identifier("LarkApp"))

/**
 * Every `LarkApp` in what is being compiled.
 *
 * This version finds them and says nothing about their graphs — reading one is the next branch.
 * What it establishes is that a checker runs, in the compiler and in the editor alike, which is the
 * part of the design that had to be proved before any analysis was worth writing.
 */
internal class ApplicationChecker(private val verbose: Boolean) : FirClassChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (!declaration.isApplication()) return
        if (verbose) reporter.reportOn(declaration.source, LarkDiagnostics.LARK_APP_FOUND, declaration.name(), context)
    }

    private fun FirClass.isApplication(): Boolean =
        superTypeRefs.any { it.coneType.classId == larkApp }

    private fun FirClass.name(): String = symbol.classId.asFqNameString()
}

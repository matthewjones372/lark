package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Calls that hand-roll something lark ships, each named where it is written (spec 0132). */
internal class HandRolledChecker(private val test: Boolean) : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val called = expression.calleeReference.toResolvedCallableSymbol()?.callableId ?: return
        if (test && called in sleeps) {
            reporter.reportOn(expression.source, LarkDiagnostics.LARK_SLEEP_IN_TEST, said(called), context)
        }
    }
}

private val sleeps = setOf(
    CallableId(ClassId(FqName("java.lang"), Name.identifier("Thread")), Name.identifier("sleep")),
    CallableId(ClassId(FqName("java.util.concurrent"), Name.identifier("TimeUnit")), Name.identifier("sleep")),
)

private fun said(called: CallableId): String = "${called.classId?.shortClassName}.${called.callableName}"

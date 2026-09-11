package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.warning1

/**
 * What the plugin can say.
 *
 * A container rather than loose factories because the compiler finds a plugin's renderers through
 * it: a diagnostic whose factory nobody registered prints as its own name and nothing else.
 */
object LarkDiagnostics : KtDiagnosticsContainer() {

    /** Proof the checker ran, off by default, and the only thing this first version says. */
    val LARK_APP_FOUND: KtDiagnosticFactory1<String> by warning1<PsiElement, String>()

    /** A key nothing in the graph provides. Not reported yet; the reader that finds one is next. */
    val LARK_APP_MISSING: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers
}

private object Renderers : BaseDiagnosticRendererFactory() {

    // The name is the compiler's, on an abstract property this has to override.
    @Suppress("ktlint:standard:property-naming")
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Lark") { map ->
        map.put(LarkDiagnostics.LARK_APP_FOUND, "lark-app: checking {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_MISSING, "lark-app: {0}", CommonRenderers.STRING)
    }
}

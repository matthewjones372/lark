package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import kotlin.reflect.KClass

/**
 * What the plugin can say.
 *
 * A container rather than loose factories because the compiler finds a plugin's renderers through
 * it: a diagnostic whose factory nobody registered prints as its own name and nothing else.
 */
object LarkDiagnostics : KtDiagnosticsContainer() {

    /** Proof the checker ran, off by default, and the only thing this first version says. */
    val LARK_APP_FOUND: KtDiagnosticFactory1<String> = factory("LARK_APP_FOUND", Severity.WARNING)

    /** A key nothing in the graph provides. Not reported yet; the reader that finds one is next. */
    val LARK_APP_MISSING: KtDiagnosticFactory1<String> = factory("LARK_APP_MISSING", Severity.ERROR)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private fun factory(name: String, severity: Severity) = KtDiagnosticFactory1<String>(
        name,
        severity,
        SourceElementPositioningStrategies.DECLARATION_NAME,
        psiElement,
        Renderers,
    )
}

/**
 * `PsiElement`, under whichever name it has where this is running.
 *
 * `kotlin-compiler-embeddable` relocates IntelliJ's classes under its own package, and the IDE runs
 * the compiler unrelocated — so a `warning1<PsiElement, _>()` written the usual way compiles to a
 * reference to the relocated name, and the checker dies with `NoClassDefFoundError` the moment the
 * editor reaches it. Resolving the name at runtime is what lets one jar serve both.
 */
private val psiElement: KClass<*> = sequenceOf(
    "org.jetbrains.kotlin.com.intellij.psi.PsiElement",
    "com.intellij.psi.PsiElement",
).firstNotNullOfOrNull { name -> runCatching { Class.forName(name).kotlin }.getOrNull() }
    ?: error("neither the relocated nor the plain PsiElement is on the classpath")

private object Renderers : BaseDiagnosticRendererFactory() {

    // The name is the compiler's, on an abstract property this has to override.
    @Suppress("ktlint:standard:property-naming")
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Lark") { map ->
        map.put(LarkDiagnostics.LARK_APP_FOUND, "lark-app: checking {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_MISSING, "lark-app: {0}", CommonRenderers.STRING)
    }
}

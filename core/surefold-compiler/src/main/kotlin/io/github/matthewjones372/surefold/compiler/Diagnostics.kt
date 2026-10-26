package io.github.matthewjones372.surefold.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import kotlin.reflect.KClass

/**
 * What the plugin can say (spec 0041). A container rather than loose factories because the compiler finds a plugin's
 * renderers through it: a diagnostic whose factory nobody registered prints as its own name and nothing else.
 */
object ForkDiagnostics : KtDiagnosticsContainer() {

    /** A `Deferred`, `Channel` or `Scope` that outlives the scope that ends it. */
    val DEFERRED_ESCAPES_SCOPE: KtDiagnosticFactory1<String> = KtDiagnosticFactory1(
        "DEFERRED_ESCAPES_SCOPE",
        Severity.ERROR,
        SourceElementPositioningStrategies.DEFAULT,
        psiElement,
        Renderers,
    )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers
}

/**
 * `PsiElement`, under whichever name it has where this is running: `kotlin-compiler-embeddable` relocates IntelliJ's
 * classes and the IDE does not, so the name is resolved at runtime, as `surefold-app-compiler` does.
 */
private val psiElement: KClass<*> = sequenceOf(
    "org.jetbrains.kotlin.com.intellij.psi.PsiElement",
    "com.intellij.psi.PsiElement",
).firstNotNullOfOrNull(::classNamed)
    ?: error("neither the relocated nor the plain PsiElement is on the classpath")

private fun classNamed(name: String): KClass<*>? =
    try {
        Class.forName(name).kotlin
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

private object Renderers : BaseDiagnosticRendererFactory() {

    // The name is the compiler's, on an abstract property this has to override.
    @Suppress("ktlint:standard:property-naming")
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("SurefoldForks") { map ->
        map.put(ForkDiagnostics.DEFERRED_ESCAPES_SCOPE, "surefold: {0}", CommonRenderers.STRING)
    }
}

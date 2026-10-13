package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
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

    /** What was read, when asked. Off by default, because a build that is working has nothing to say. */
    val LARK_APP_FOUND: KtDiagnosticFactory1<String> =
        factory("LARK_APP_FOUND", Severity.WARNING, SourceElementPositioningStrategies.DECLARATION_NAME)

    /**
     * A graph this could not read, said whether or not anybody asked.
     *
     * Otherwise silence means two things — the graph is sound, and the graph was never looked at —
     * and a reader who takes the first for the second has been told the opposite of the truth.
     */
    val LARK_APP_UNREAD: KtDiagnosticFactory1<String> =
        factory("LARK_APP_UNREAD", Severity.WARNING, SourceElementPositioningStrategies.DECLARATION_NAME)

    /** A key nothing in the graph provides. */
    // Reported on the call that asked, which is not a declaration: `DECLARATION_NAME` casts its
    // source to one, and the editor drops every diagnostic whose strategy throws. The compiler is
    // more forgiving, so the cost of getting this wrong is silence in the only place it matters.
    val LARK_APP_MISSING: KtDiagnosticFactory1<String> =
        factory("LARK_APP_MISSING", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT)

    /** A node the root does not reach. A warning, because `rootFindings` gives it `Severity.WARN`. */
    val LARK_APP_UNREACHABLE: KtDiagnosticFactory1<String> =
        factory("LARK_APP_UNREACHABLE", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A key two recipes provide. A warning, as `Findings.kt` gives a `Duplicate` `Severity.WARN`. */
    val LARK_APP_DUPLICATE: KtDiagnosticFactory1<String> =
        factory("LARK_APP_DUPLICATE", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A ring of recipes. An error, as `Findings.kt` gives a `Cycle` `Severity.FAIL`. */
    val LARK_APP_CYCLE: KtDiagnosticFactory1<String> =
        factory("LARK_APP_CYCLE", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private fun factory(
        name: String,
        severity: Severity,
        where: AbstractSourceElementPositioningStrategy,
    ) = KtDiagnosticFactory1<String>(name, severity, where, psiElement, Renderers)
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
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Lark") { map ->
        map.put(LarkDiagnostics.LARK_APP_FOUND, "lark-app: {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_MISSING, "lark-app: {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_UNREACHABLE, "lark-app: {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_DUPLICATE, "lark-app: {0}", CommonRenderers.STRING)
        map.put(LarkDiagnostics.LARK_APP_CYCLE, "lark-app: {0}", CommonRenderers.STRING)
        map.put(
            LarkDiagnostics.LARK_APP_UNREAD,
            "lark-app: this graph was not read here, and is checked by larkWiring alone: {0}",
            CommonRenderers.STRING,
        )
    }
}

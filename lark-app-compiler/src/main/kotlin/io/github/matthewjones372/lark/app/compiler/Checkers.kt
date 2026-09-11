package io.github.matthewjones372.lark.app.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** Registers the checkers against a session, which is what both the compiler and the IDE build. */
class LarkFirExtensionRegistrar(private val verbose: Boolean) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> LarkCheckers(session, verbose) }
    }
}

internal class LarkCheckers(session: FirSession, verbose: Boolean) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(ApplicationChecker(verbose))
    }
}

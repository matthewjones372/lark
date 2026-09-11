package io.github.matthewjones372.lark.app

import kotlin.reflect.KType

/**
 * An application as a value: the graph, the node it starts from, and what to do with that node.
 *
 * The root is a constructor argument rather than only a type parameter because a build-time check
 * has to read it without running `main`, and a type parameter is erased by then. `object Petshop :
 * LarkApp<PelicanServer>(typeOf<PelicanServer>())`.
 */
abstract class LarkApp<A : Any>(val root: KType) {

    abstract val module: Module

    abstract fun AppScope.run(root: A)
}

/** [runApp] for an application that declares its own root, so `main` is the leaving and nothing else. */
fun <A : Any> runApp(app: LarkApp<A>): ExitCode = leaving { shutdown ->
    app.module.use(app.root) { built ->
        @Suppress("UNCHECKED_CAST")
        with(app) { appScope(shutdown).run(built as A) }
    }
}

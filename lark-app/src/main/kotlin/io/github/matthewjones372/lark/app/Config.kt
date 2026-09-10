package io.github.matthewjones372.lark.app

/**
 * Where configuration comes from, and nothing about what tool it came from.
 *
 * A path is dotted — `database.url` — and a source decides what that means: an environment variable,
 * a key in a map, a node in a YAML tree. Hoplite, Typesafe Config and a properties file are each one
 * implementation of this and none of them is in this module.
 */
fun interface Config {

    /** The value at [path], or null where the source does not have one. */
    fun at(path: String): String?
}

/** The values in [entries], for a test that would rather not have a file. */
fun configOf(entries: Map<String, String>): Config = Config { path -> entries[path] }

fun configOf(vararg entries: Pair<String, String>): Config = configOf(entries.toMap())

/** This source, and [fallback] wherever this one has nothing: the shape a chain of sources has. */
fun Config.orElse(fallback: Config): Config = Config { path -> at(path) ?: fallback.at(path) }

/**
 * The process environment as a [Config]: `database.url` is read as `DATABASE_URL`, which is the
 * spelling a container hands a service.
 */
fun Sys.asConfig(): Config = Config { path -> env(path.uppercase().replace('.', '_')) ?: property(path) }

/**
 * The module the value at [path] names, which is the `when` a service writes over its own settings —
 * a Redis cart store or a Postgres one, a broker or a simulator — as a value rather than a branch
 * buried in `main`.
 *
 * The choice is made while the graph is being described, before anything is built: a `Module` is
 * data, and the branch not taken contributes no node, no dependency and nothing to start. Naming a
 * branch that does not exist is a deployment mistake rather than a declared outcome, so it is refused
 * here, with what the value was and what it could have been.
 */
fun Config.choose(path: String, default: String? = null, vararg choices: Pair<String, Module>): Module {
    val named = at(path) ?: default
    return requireNotNull(choices.toMap()[named]) {
        "$path is ${named ?: "not set"}, and must name one of: ${choices.joinToString { it.first }}"
    }
}

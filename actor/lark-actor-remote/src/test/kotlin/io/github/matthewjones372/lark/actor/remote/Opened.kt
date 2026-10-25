package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.junit.Expectations

/** What one test opened, closed in reverse once it ends: a list of its own, since a suite's tests run at once. */
internal class Opened {
    private val toClose = mutableListOf<AutoCloseable>()

    fun <T : AutoCloseable> T.closedAfter(): T = also { toClose += it }

    fun close() = toClose.asReversed().forEach(AutoCloseable::close)
}

/** Runs [body], then closes what it opened, as an `@AfterEach` did. */
internal fun opening(body: Opened.() -> Expectations): Expectations {
    val opened = Opened()
    return try {
        opened.body()
    } finally {
        opened.close()
    }
}

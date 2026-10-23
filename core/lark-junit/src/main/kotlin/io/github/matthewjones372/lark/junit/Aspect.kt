package io.github.matthewjones372.lark.junit

import org.junit.platform.engine.TestTag

/**
 * Something said about how a test runs, given beside its name or to a [Suite] for each of its tests. In `a + b`,
 * `a` wraps `b`, and a suite's aspects wrap each of its tests'. Aspects are known when a test is registered, so
 * discovery reads its tags without running anything.
 */
class Aspect internal constructor(
    internal val tags: Set<TestTag> = emptySet(),
    internal val ignored: String? = null,
    internal val alone: Boolean = false,
    private val arounds: List<(() -> Unit) -> Unit> = emptyList(),
) {

    /** Both sides' tags, `alone` if either is, and ignored for the outer side's reason before the inner's. */
    operator fun plus(inner: Aspect): Aspect =
        Aspect(tags + inner.tags, ignored ?: inner.ignored, alone || inner.alone, arounds + inner.arounds)

    /** Runs [test] inside every around, the leftmost outermost. */
    internal fun run(test: () -> Unit) = arounds.foldRight(test) { around, inner -> { around(inner) } }()

    internal companion object {
        val none = Aspect()

        /** Runs a test through [around], which is handed the test to call. */
        fun around(around: (() -> Unit) -> Unit) = Aspect(arounds = listOf(around))
    }
}

/** A JUnit tag, so the platform's tag filters, Gradle's `includeTags` among them, select the test by [name]. */
fun tag(name: String): Aspect = Aspect(tags = setOf(TestTag.create(name)))

/** Reports the test skipped, for [why], without running it. */
fun ignore(why: String): Aspect = Aspect(ignored = why)

/** Nothing else in the suite runs beside the test: it waits for those registered before it, and later ones wait. */
val alone: Aspect = Aspect(alone = true)

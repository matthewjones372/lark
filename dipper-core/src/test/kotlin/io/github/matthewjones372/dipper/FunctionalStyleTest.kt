package io.github.matthewjones372.dipper

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where mutable state is allowed to live, stated as a test.
 *
 * The list is empty: nothing in the library accumulates, because a `Stream` is
 * a description handed to Pekko and Pekko does the collecting. A file that
 * needs an accumulator has to be argued for here, in writing, before the build
 * goes green again.
 *
 * Which sources it judges is not decided here: the build hands them over and
 * declares the same directories as inputs of the task that runs this test. See
 * `dipper-core/build.gradle.kts`.
 */
class FunctionalStyleTest {

    private val builders = emptyMap<String, String>()

    private val accumulators = Regex(
        """\b(mutableListOf|mutableMapOf|mutableSetOf""" +
            """|LinkedHashMap|LinkedHashSet|IdentityHashMap|ArrayList|HashMap|HashSet)\s*[(<]""",
    )

    /** Absent means the build's wiring is gone, which is the failure this test cannot survive. */
    private fun handedOver(name: String): String {
        val value = System.getProperty(name)
        withClue("the build must pass -D$name; see dipper-core/build.gradle.kts") { value.shouldNotBeNull() }
        return value!!
    }

    private fun repoRoot(): File = File(handedOver("dipper.style.repoRoot"))

    private fun sourceRoots(): List<File> = handedOver("dipper.style.sources")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)

    private fun mainSources(): List<File> = sourceRoots()
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    @Test
    fun `the build hands this test the sources it judges`() {
        val roots = sourceRoots()
        withClue("the build named no source roots at all") { roots.shouldNotBeEmpty() }

        val missing = roots.filterNot { it.isDirectory }
        withClue("the build named source roots that are not there: $missing") { missing.shouldBeEmpty() }

        val outside = roots.filterNot { it.absoluteFile.startsWith(repoRoot().absoluteFile) }
        withClue("a source root sits outside ${repoRoot()}, so the paths below cannot be keys: $outside") {
            outside.shouldBeEmpty()
        }

        withClue("no sources were handed over; the gate is judging nothing") {
            mainSources().shouldNotBeEmpty()
        }
    }

    @Test
    fun `mutable collections are built only where a builder was meant to be`() {
        val root = repoRoot()
        val found = mainSources()
            .filter { accumulators.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toSortedSet()

        val unexpected = found - builders.keys
        withClue("a mutable accumulator appeared outside a builder: $unexpected") {
            unexpected.shouldBeEmpty()
        }

        val stale = builders.keys - found
        withClue("these no longer accumulate; drop them from the list: $stale") {
            stale.shouldBeEmpty()
        }
    }

    @Test
    fun `nothing in the library holds a var`() {
        val vars = mainSources()
            .filter { Regex("""(^|\s)var\s""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot()).path }

        withClue("a `var` appeared in the library: $vars") { vars.shouldBeEmpty() }
    }
}

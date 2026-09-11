package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

private class Spanner
private class Bolt

/**
 * `single` and `singleOf` are inline, so the frame above `module` is already the caller's. The claim
 * worth testing is that a wrapper in another lark module does not get named instead of the graph.
 */
class CallSiteTest {

    @Test
    fun `a node remembers the file and line it was written on`() {
        val module = single { _: Spanner -> Bolt() }

        val site = module.nodes.values.single().site.shouldNotBeNull()

        site shouldMatch Regex("""io/github/matthewjones372/lark/app/CallSiteTest\.kt:\d+""")
    }

    @Test
    fun `a report names the file rather than the path the site carries for a build tool`() {
        val report = single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull().report()

        withClue("the package is there to resolve a link, not for a person to read past") {
            report shouldContain "CallSiteTest.kt:"
            report shouldNotContain "io/github/matthewjones372"
        }
    }

    @Test
    fun `a node built through a library factory names the graph rather than the factory`() {
        val module = viaFactory()

        withClue("wrapping single in a lark factory must not name that factory's own file") {
            module.nodes.values.single().site.shouldNotBeNull() shouldContain "CallSiteTest.kt"
        }
    }

    @Test
    fun `re-keying keeps the site the recipe was written on`() {
        val module = singleOf(::Bolt).boundTo<Any>()

        module.nodes.values.single().site.shouldNotBeNull() shouldContain "CallSiteTest.kt"
    }

    @Test
    fun `a missing dependency reports the site of the recipe that asked`() {
        val report = single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull().report()

        report shouldContain "missing Spanner"
        report shouldMatch Regex("""(?s).*for Bolt\s+CallSiteTest\.kt:\d+.*""")
    }
}

/** Stands in for `actor` or `migrations`: a lark-package top-level function that is not inline. */
private fun viaFactory(): Module = single<Spanner> { Spanner() }

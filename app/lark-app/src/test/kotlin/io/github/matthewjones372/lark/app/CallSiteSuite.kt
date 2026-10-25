package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource

private class Spanner
private class Bolt

/**
 * `single` and `singleOf` are inline, so the frame above `module` is already the caller's. The claim
 * worth testing is that a wrapper in another lark module does not get named instead of the graph.
 */
object CallSiteSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a node remembers the file and line it was written on") { _ ->
            val module = single { _: Spanner -> Bolt() }

            module.nodes.values.single().site.shouldNotBeNull { site ->
                expect(Regex("""io/github/matthewjones372/lark/app/CallSiteSuite\.kt:\d+""").matches(site))
            }
        }

        test("a report names the file rather than the path the site carries for a build tool") { _ ->
            single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull { errors ->
                val report = errors.report()

                withClue("the package is there to resolve a link, not for a person to read past") {
                    (report shouldContain "CallSiteSuite.kt:") and (report shouldNotContain "io/github/matthewjones372")
                }
            }
        }

        test("a node built through a library factory names the graph rather than the factory") { _ ->
            val module = viaFactory()

            withClue("wrapping single in a lark factory must not name that factory's own file") {
                module.nodes.values.single().site.shouldNotBeNull { site -> site shouldContain "CallSiteSuite.kt" }
            }
        }

        test("re-keying keeps the site the recipe was written on") { _ ->
            val module = singleOf(::Bolt).boundTo<Any>()

            module.nodes.values.single().site.shouldNotBeNull { site -> site shouldContain "CallSiteSuite.kt" }
        }

        test("a missing dependency reports the site of the recipe that asked") { _ ->
            single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull { errors ->
                val report = errors.report()

                (report shouldContain "missing Spanner") and
                    expect(Regex("""(?s).*for Bolt\s+CallSiteSuite\.kt:\d+.*""").matches(report))
            }
        }
    }
}

/** Stands in for `actor` or `migrations`: a lark-package top-level function that is not inline. */
private fun viaFactory(): Module = single<Spanner> { Spanner() }

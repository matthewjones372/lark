package io.github.matthewjones372.lark

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LarkLocalTest {

    @Test
    fun `an unbound local answers with its initial value`() {
        val local = larkLocal { "initial" }

        local.get() shouldBe "initial"
    }

    @Test
    fun `a binding is put back when its block leaves`() {
        val local = larkLocal { "initial" }

        local.locally("bound") { local.get() } shouldBe "bound"
        local.get() shouldBe "initial"
    }

    @Test
    fun `a value bound outside a parMap is readable in every branch`() {
        val local = larkLocal { "initial" }

        val seen = local.locally("bound") { parMap(listOf(1, 2, 3)) { local.get() } }

        seen shouldContainExactly listOf("bound", "bound", "bound")
    }

    @Test
    fun `a branch does not see a binding made after it forked`() {
        val local = larkLocal { "initial" }
        val forked = CountDownLatch(1)
        val bound = CountDownLatch(1)

        val seen = flock<Nothing, String> {
            val branch = async {
                forked.countDown()
                bound.await()
                local.get()
            }
            forked.await()
            local.locally("later") { bound.countDown() }
            branch.await()
        }

        seen.getOrNull().shouldNotBeNull() shouldBe "initial"
    }

    @Test
    fun `a binding made inside a branch does not reach its siblings`() {
        val local = larkLocal { "initial" }

        val seen = parZip(
            { local.locally("mine") { local.get() } },
            { local.get() },
        ) { first, second -> first to second }

        seen shouldBe ("mine" to "initial")
    }

    @Test
    fun `a fork on a given executor inherits too`() {
        val pool = Executors.newFixedThreadPool(2)
        val local = larkLocal { "initial" }

        try {
            val seen = local.locally("bound") { parMap(on = pool, iterable = listOf(1, 2)) { local.get() } }

            withClue("the capture is at the fork, not inside VirtualThreads") {
                seen shouldContainExactly listOf("bound", "bound")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

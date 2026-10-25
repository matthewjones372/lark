package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.util.concurrent.CopyOnWriteArrayList

object ClosingOfSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    // Each test hands these a list of its own: the suite is one object, so a list in a field would be every test's.
    private class Pool(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "pool"
        }
    }

    private class Client(val pool: Pool, val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "client"
        }
    }

    private class Broken : AutoCloseable {
        override fun close() = error("would not close")
    }

    init {
        test("a node built by closingOf is closed on release") { _ ->
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) }

            val held = testApp(app) { _: Pool ->
                withClue("not while the block holds it") { closed shouldBe emptyList() }
            }

            held and (closed shouldBe listOf("pool"))
        }

        test("in reverse dependency order, so a client is closed before the pool it holds") { _ ->
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) } + closingOf { pool: Pool -> Client(pool, closed) }

            testApp(app) { _: Client -> }

            closed shouldBe listOf("client", "pool")
        }

        test("a close that throws is reported, and the nodes around it are closed anyway") { _ ->
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) } + closingOf { _: Pool -> Broken() }

            val thrown = shouldThrow<IllegalStateException>({ testApp(app) { _: Broken -> } }) { thrown ->
                thrown.message shouldBe "would not close"
            }

            thrown and (closed shouldBe listOf("pool"))
        }
    }
}

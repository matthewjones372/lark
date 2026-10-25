package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class ClosingOfTest {

    private val closed = CopyOnWriteArrayList<String>()

    private inner class Pool : AutoCloseable {
        override fun close() {
            closed += "pool"
        }
    }

    private inner class Client(val pool: Pool) : AutoCloseable {
        override fun close() {
            closed += "client"
        }
    }

    private class Broken : AutoCloseable {
        override fun close() = error("would not close")
    }

    @Test
    fun `a node built by closingOf is closed on release`() {
        val app = closingOf { -> Pool() }

        testApp(app) { _: Pool -> withClue("not while the block holds it") { closed shouldBe emptyList() } }

        closed shouldBe listOf("pool")
    }

    @Test
    fun `in reverse dependency order, so a client is closed before the pool it holds`() {
        val app = closingOf { -> Pool() } + closingOf { pool: Pool -> Client(pool) }

        testApp(app) { _: Client -> }

        closed shouldBe listOf("client", "pool")
    }

    @Test
    fun `a close that throws is reported, and the nodes around it are closed anyway`() {
        val app = closingOf { -> Pool() } + closingOf { _: Pool -> Broken() }

        val thrown = shouldThrow<IllegalStateException> { testApp(app) { _: Broken -> } }

        thrown.message shouldBe "would not close"
        closed shouldBe listOf("pool")
    }
}

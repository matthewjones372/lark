package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf

private class Root
private class Left
private class Right
private class Leaf

class StartTest {

    @Test
    fun `the root is handed to the block`() {
        val module = single<Leaf> { Leaf() } + single { _: Leaf -> Root() }

        module.use { root: Root -> root }.getOrNull().shouldNotBeNull()
    }

    @Test
    fun `a node is built once, however many need it`() {
        val built = AtomicInteger()
        val module = single<Leaf> { built.incrementAndGet(); Leaf() } +
            single { _: Leaf -> Left() } +
            single { _: Leaf -> Right() } +
            single { _: Left, _: Right -> Root() }

        module.use { _: Root -> }.getOrNull().shouldNotBeNull()

        built.get() shouldBe 1
    }

    @Test
    fun `independent nodes start on virtual threads of their own`() {
        val threads = ConcurrentHashMap.newKeySet<String>()
        val virtual = ConcurrentHashMap.newKeySet<Boolean>()
        fun record() {
            threads.add(Thread.currentThread().name + Thread.currentThread().threadId())
            virtual.add(Thread.currentThread().isVirtual)
        }

        val module = single<Left> { record(); Left() } +
            single<Right> { record(); Right() } +
            single { _: Left, _: Right -> Root() }

        module.use { _: Root -> }.getOrNull().shouldNotBeNull()

        threads shouldHaveSize 2
        virtual shouldContainExactly setOf(true)
    }

    @Test
    fun `a node that refuses leaves nothing acquired`() {
        val released = mutableListOf<String>()
        val module = single<Leaf> { install({ Leaf() }) { _, _ -> released += "leaf" } } +
            single<Root, Leaf> { refuse("the port is already taken") }

        val error = module.use { _: Root -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.Refused>().reason shouldBe "the port is already taken"
        withClue("what was acquired before the refusal is given back") {
            released shouldContainExactly listOf("leaf")
        }
    }

    @Test
    fun `releases run in reverse topological order`() {
        val released = mutableListOf<String>()
        val module = single<Leaf> { install({ Leaf() }) { _, _ -> released += "leaf" } } +
            single { _: Leaf -> install({ Left() }) { _, _ -> released += "left" } } +
            single { _: Left -> install({ Root() }) { _, _ -> released += "root" } }

        module.use { _: Root -> }.getOrNull().shouldNotBeNull()

        released shouldContainExactly listOf("root", "left", "leaf")
    }

    @Test
    fun `a graph that does not validate never builds`() {
        val module = single<Root, Leaf> { error("a recipe must not run when the graph is short of a key") }

        val error = module.use { _: Root -> }.leftOrNull().shouldNotBeNull()

        val fault = error.shouldBeInstanceOf<StartupError.Unwireable>().errors.head
        fault.shouldBeInstanceOf<WiringError.Missing>().copy(site = null) shouldBe
            WiringError.Missing(key = typeOf<Leaf>(), neededBy = typeOf<Root>())
    }

    @Test
    fun `asking for a type the graph does not build names it`() {
        val error = single<Leaf> { Leaf() }.use { _: Root -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.NoSuchNode>().key shouldBe typeOf<Root>()
    }
}

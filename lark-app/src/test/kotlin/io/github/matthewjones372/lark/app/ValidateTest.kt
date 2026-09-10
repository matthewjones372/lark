package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

private class Settings
private class Pipe
private class Store
private class Buffer

class ValidateTest {

    @Test
    fun `a graph with no edges plans as one layer`() {
        val plan = (single<Settings> { Settings() } + single<Buffer> { Buffer() })
            .validate().getOrNull().shouldNotBeNull()

        plan.layers.size shouldBe 1
    }

    @Test
    fun `a dependency is planned before what needs it`() {
        val module = single<Settings> { Settings() } +
            single { _: Settings -> Pipe() } +
            single { _: Pipe -> Store() }

        val plan = module.validate().getOrNull().shouldNotBeNull()

        plan.layers shouldContainExactly listOf(
            listOf(typeOf<Settings>()),
            listOf(typeOf<Pipe>()),
            listOf(typeOf<Store>()),
        )
    }

    @Test
    fun `independent nodes share a layer`() {
        val module = single<Settings> { Settings() } +
            single { _: Settings -> Pipe() } +
            single { _: Settings -> Buffer() }

        val plan = module.validate().getOrNull().shouldNotBeNull()

        withClue("Pipe and Buffer each need only Settings, so neither waits for the other") {
            plan.layers[1].toSet() shouldBe setOf(typeOf<Pipe>(), typeOf<Buffer>())
        }
    }

    @Test
    fun `validate runs no recipe body`() {
        val module = single<Settings> { Settings() } +
            single<Pipe, Settings> { error("a recipe must not run to be validated") }

        module.validate().getOrNull().shouldNotBeNull()
    }

    @Test
    fun `a missing dependency is named with what needed it`() {
        val errors = single { _: Settings -> Pipe() }.validate().leftOrNull().shouldNotBeNull()

        errors.head.shouldBeInstanceOf<WiringError.Missing>() shouldBe
            WiringError.Missing(key = typeOf<Settings>(), neededBy = typeOf<Pipe>())
    }

    @Test
    fun `a cycle names the path around it`() {
        val module = single { _: Store -> Pipe() } + single { _: Pipe -> Store() }

        val errors = module.validate().leftOrNull().shouldNotBeNull()
        val path = errors.head.shouldBeInstanceOf<WiringError.Cycle>().path

        withClue("the path ends where it began") {
            path.first() shouldBe path.last()
            path.toSet() shouldBe setOf(typeOf<Pipe>(), typeOf<Store>())
        }
    }

    @Test
    fun `the report names every consumer of a missing key`() {
        val module = single { _: Settings -> Pipe() } + single { _: Settings -> Buffer() }

        val report = module.validate().leftOrNull().shouldNotBeNull().report()

        report shouldContain "❯ missing Settings"
        report shouldContain "❯     for Pipe"
        report shouldContain "❯     for Buffer"
    }
}

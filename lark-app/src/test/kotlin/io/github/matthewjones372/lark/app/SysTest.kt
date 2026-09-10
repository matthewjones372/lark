package io.github.matthewjones372.lark.app

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SysTest {

    private val sys = FakeSys(
        env = mapOf("DB_URL" to "jdbc:h2:mem:", "PORT" to "8080", "DEBUG" to "true", "WAIT" to "5s"),
        properties = mapOf("java.vm.name" to "VM"),
    )

    @Test
    fun `a fake reads what it was given and nothing of the process`() {
        sys.env("DB_URL") shouldBe "jdbc:h2:mem:"
        sys.property("java.vm.name") shouldBe "VM"
        sys.env("PATH").shouldBe(null)
    }

    @Test
    fun `the real one reads the process`() {
        RealSys.env().isEmpty() shouldBe false
        RealSys.property("java.vm.name").shouldNotBeNull()
    }

    @Test
    fun `a required name that is absent says which one`() {
        val error = sys.required("SECRET").leftOrNull().shouldNotBeNull()

        error shouldBe ConfigError.Missing("SECRET")
    }

    @Test
    fun `a value is read as what it was asked for`() {
        sys.required("DB_URL").getOrNull() shouldBe "jdbc:h2:mem:"
        sys.int("PORT").getOrNull() shouldBe 8080
        sys.boolean("DEBUG").getOrNull() shouldBe true
        sys.duration("WAIT").getOrNull() shouldBe 5.seconds
        sys.optional("NOTHING") shouldBe null
    }

    @Test
    fun `a value that is not what it was asked for says so, and says what it was`() {
        val error = FakeSys(mapOf("PORT" to "eighty-eighty")).int("PORT").leftOrNull().shouldNotBeNull()

        val notA = error.shouldBeInstanceOf<ConfigError.NotA>()
        notA.name shouldBe "PORT"
        notA.expected shouldBe "an Int"
        notA.value shouldBe "eighty-eighty"
    }

    @Test
    fun `a graph reads its configuration through the bound Sys`() {
        val module = single<Sys> { sys } +
            single { bound: Sys -> Port(bound.int("PORT").getOrNull() ?: 0) }

        testApp(module) { port: Port -> port.number } shouldBe 8080
    }
}

private class Port(val number: Int)

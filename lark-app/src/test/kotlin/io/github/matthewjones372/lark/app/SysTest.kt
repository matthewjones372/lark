package io.github.matthewjones372.lark.app

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private class Port(val number: Int)

/** The process, and nothing about what a configuration library would do with it. */
class SysTest {

    private val sys = FakeSys(
        env = mapOf("DB_URL" to "jdbc:postgresql://db/app", "PORT" to "8080"),
        properties = mapOf("java.vm.name" to "VM"),
    )

    @Test
    fun `a fake reads what it was given and nothing of the process`() {
        sys.env("DB_URL") shouldBe "jdbc:postgresql://db/app"
        sys.property("java.vm.name") shouldBe "VM"
        sys.env("PATH") shouldBe null
    }

    @Test
    fun `the real one reads the process`() {
        RealSys.env().isEmpty() shouldBe false
        RealSys.property("java.vm.name").shouldNotBeNull()
    }

    @Test
    fun `a node takes the process rather than calling System getenv`() {
        val app = single<Sys> { sys } + single { bound: Sys -> Port(bound.env("PORT")!!.toInt()) }

        testApp(app) { port: Port -> port.number } shouldBe 8080
    }
}

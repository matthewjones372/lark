package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.resource

private class Port(val number: Int)

/** The process, and nothing about what a configuration library would do with it. */
object SysSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val sys = FakeSys(
        env = mapOf("DB_URL" to "jdbc:postgresql://db/app", "PORT" to "8080"),
        properties = mapOf("java.vm.name" to "VM"),
    )

    init {
        test("a fake reads what it was given and nothing of the process") { _ ->
            (sys.env("DB_URL") shouldBe "jdbc:postgresql://db/app") and
                (sys.property("java.vm.name") shouldBe "VM") and
                (sys.env("PATH") shouldBe null)
        }

        test("the real one reads the process") { _ ->
            (RealSys.env().isEmpty() shouldBe false) and RealSys.property("java.vm.name").shouldNotBeNull()
        }

        test("a node takes the process rather than calling System getenv") { _ ->
            val app = single<Sys> { sys } + single { bound: Sys -> Port(bound.env("PORT")!!.toInt()) }

            testApp(app) { port: Port -> port.number } shouldBe 8080
        }
    }
}

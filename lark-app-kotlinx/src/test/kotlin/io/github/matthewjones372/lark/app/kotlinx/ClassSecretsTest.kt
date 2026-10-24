package io.github.matthewjones372.lark.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.MASK
import io.github.matthewjones372.lark.app.typesafe.Secrets
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.typesafe.origins
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldNotContain
import io.github.matthewjones372.lark.assert.withClue
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

@Serializable
private data class Store(val dbUrl: Secret, val replica: Replica)

@Serializable
private data class Replica(val readUrl: Secret)

class ClassSecretsTest {

    @Test
    fun `a Secret property masks itself in toString and in the report`() {
        val hocon =
            """
            petshop {
              dbUrl = "jdbc:postgresql://user:hunter2@db/shop"
              replica { readUrl = "jdbc:postgresql://user:hunter3@replica/shop" }
            }
            """

        val store = testApp(configOf(hocon) + config<Store>("petshop")) { store: Store -> store }

        store.dbUrl.reveal() shouldBe "jdbc:postgresql://user:hunter2@db/shop"
        store.toString() shouldNotContain "hunter"
        store.toString() shouldContain MASK
        val report = ConfigFactory.parseString(hocon).origins(Secrets.default.andSecretsOf<Store>("petshop")).report()
        withClue("neither name says secret, so only the class can tell the report") {
            report shouldNotContain "hunter"
            report shouldContain "petshop.replica.readUrl"
        }
    }
}

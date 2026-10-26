package io.github.matthewjones372.surefold.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.typesafe.MASK
import io.github.matthewjones372.surefold.app.typesafe.Secrets
import io.github.matthewjones372.surefold.app.typesafe.configOf
import io.github.matthewjones372.surefold.app.typesafe.origins
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.shouldNotContain
import io.github.matthewjones372.surefold.junit.withClue
import kotlinx.serialization.Serializable

@Serializable
private data class Store(val dbUrl: Secret, val replica: Replica)

@Serializable
private data class Replica(val readUrl: Secret)

object ClassSecretsSuite : Suite() {
    init {
        test("a Secret property masks itself in toString and in the report") {
            val hocon =
                """
                petshop {
                  dbUrl = "jdbc:postgresql://user:hunter2@db/shop"
                  replica { readUrl = "jdbc:postgresql://user:hunter3@replica/shop" }
                }
                """

            val store = testApp(configOf(hocon) + config<Store>("petshop")) { store: Store -> store }

            val masked = (store.dbUrl.reveal() shouldBe "jdbc:postgresql://user:hunter2@db/shop") and
                (store.toString() shouldNotContain "hunter") and
                (store.toString() shouldContain MASK)
            val report =
                ConfigFactory.parseString(hocon).origins(Secrets.default.andSecretsOf<Store>("petshop")).report()
            masked and
                withClue("neither name says secret, so only the class can tell the report") {
                    (report shouldNotContain "hunter") and
                        (report shouldContain "petshop.replica.readUrl")
                }
        }
    }
}

package io.github.matthewjones372.lark.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.typesafe.PathRead
import io.github.matthewjones372.lark.app.typesafe.reading
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test

@Serializable
private data class Shop(val host: String, val mode: Trading, val database: Database)

@Serializable
private data class Database(val url: Secret, val poolSize: Int)

@Serializable
private enum class Trading { Live, Demo }

/** A class read lists the paths it read as the block form does, its `Secret` marked (spec 0155). */
class ClassPathsReadTest {

    @Test
    fun `a class with a Secret and a nested section lists exactly its paths, the secret marked`() {
        val hocon = ConfigFactory.parseString(
            """
            petshop {
              host = "0.0.0.0", mode = Live, unread = 1
              database { url = "jdbc:postgresql://user:pass@db/shop", poolSize = 4 }
            }
            """.trimIndent(),
        )

        val paths = hocon.reading {
            section("petshop") { decoded(serializer<Shop>(), Naming.AsDeclared) }
            paths
        }.getOrNull()

        withClue("the url's name does not say secret; only its type does") {
            paths.orEmpty() shouldContainExactly listOf(
                PathRead("petshop.host", secret = false),
                PathRead("petshop.mode", secret = false),
                PathRead("petshop.database.url", secret = true),
                PathRead("petshop.database.poolSize", secret = false),
            )
        }
    }
}

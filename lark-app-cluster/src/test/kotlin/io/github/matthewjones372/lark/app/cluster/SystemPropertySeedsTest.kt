package io.github.matthewjones372.lark.app.cluster

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.app.typesafe.reading
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.util.Properties

/** Seeds given as `-Dpath.static.seeds.0=…`, as JAVA_OPTS gives them: an object Typesafe reads as a list. */
class SystemPropertySeedsTest {

    @Test
    fun `seeds overridden by numbered system properties are a list, in their numbers' order`() {
        val properties = Properties().apply {
            setProperty("lark.cluster.static.seeds.1", "bank-2:25520")
            setProperty("lark.cluster.static.seeds.0", "bank-1:25520")
            setProperty("lark.cluster.static.seeds.2", "bank-3:25520")
        }
        val file = ConfigFactory.parseString(
            """
            lark.cluster { node { name = a, port = 25520 }, join = static, static.seeds = ["127.0.0.1:25520"] }
            """.trimIndent(),
        )
        val section = ConfigFactory.parseProperties(properties).withFallback(file).resolve().getConfig("lark.cluster")

        val settings = section.reading { clusterSettings("lark.cluster") }.getOrNull().shouldNotBeNull()

        settings.joining().use { joining ->
            joining.discovery.seeds() shouldContainExactly
                listOf(Node("", "bank-1", 25520), Node("", "bank-2", 25520), Node("", "bank-3", 25520))
        }
    }
}

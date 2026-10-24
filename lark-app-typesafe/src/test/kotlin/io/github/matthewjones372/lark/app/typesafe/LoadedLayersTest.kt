package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContainExactly
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * `layeredConfig()` is `ConfigFactory.load()` kept apart, so the two agree on the merge in every case
 * `load()` reads differently: a replacement file, the environment forced on top, and the other syntaxes.
 */
class LoadedLayersTest {

    private val set = mutableListOf<String>()

    private fun property(name: String, value: String) {
        System.setProperty(name, value)
        set += name
        ConfigFactory.invalidateCaches()
    }

    @AfterEach
    fun cleared() {
        set.forEach(System::clearProperty)
        ConfigFactory.invalidateCaches()
    }

    private fun Layered.names() = layers.map { it.name }

    @Test
    fun `with nothing set, the merge is what load reads`() {
        val layered = layeredConfig()

        layered.config shouldBe ConfigFactory.load()
        layered.names() shouldContainExactly listOf(
            "system properties",
            "application.conf",
            "application.json",
            "application.properties",
            "reference.conf",
        )
    }

    @Test
    fun `application properties are read below application conf`() {
        val layered = layeredConfig()

        layered.config.getString("lark.layered.properties") shouldBe "from-properties"
        layered.layers.single { it.config.hasPath("lark.layered.properties") }.name shouldBe "application.properties"
    }

    @Test
    fun `config file replaces the application files`() {
        val file = Files.createTempFile("lark-layered", ".conf")
        try {
            Files.writeString(file, "lark.layered.file = from-file")
            property("config.file", file.toString())

            val layered = layeredConfig()

            layered.config shouldBe ConfigFactory.load()
            layered.config.getString("lark.layered.file") shouldBe "from-file"
            withClue("config.file replaces application.*, it does not sit beside them") {
                layered.config.hasPath("lark.layered.properties") shouldBe false
            }
            layered.names() shouldContainExactly listOf("system properties", file.toString(), "reference.conf")
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `CONFIG_FORCE_ variables sit above system properties once asked for`() {
        property("lark.layered.forced", "from-property")
        property("config.override_with_env_vars", "true")

        val layered = layeredConfig()

        layered.config shouldBe ConfigFactory.load()
        layered.config.getString("lark.layered.forced") shouldBe "from-env"
        layered.names().take(2) shouldContainExactly listOf("environment overrides", "system properties")
    }

    @Test
    fun `CONFIG_FORCE_ variables are not read unless asked for`() {
        property("lark.layered.forced", "from-property")

        layeredConfig().config.getString("lark.layered.forced") shouldBe "from-property"
    }

    @Test
    fun `loadedConfig provides the layers and their merge`() {
        testApp(loadedConfig()) { layered: Layered -> layered.names().last() } shouldBe "reference.conf"
        testApp(loadedConfig()) { config: Config -> config } shouldBe ConfigFactory.load()
    }

    @Test
    fun `a document written in the test is one layer`() {
        val layered = testApp(configOf("a = 1")) { layered: Layered -> layered }

        layered.layers.size shouldBe 1
        layered.config.getInt("a") shouldBe 1
    }

    @Test
    fun `overridingConfig puts its document on top of the layers`() {
        val base = layeredConfigOf("base" to ConfigFactory.parseString("a = 1, b = 2"))
        val app = configOf("a = 1, b = 2").overridingConfig("a = 3", fallback = base)

        testApp(app) { layered: Layered -> layered.names() } shouldContainExactly listOf("overrides", "base")
        testApp(app) { config: Config -> config.getInt("a") to config.getInt("b") } shouldBe (3 to 2)
    }
}

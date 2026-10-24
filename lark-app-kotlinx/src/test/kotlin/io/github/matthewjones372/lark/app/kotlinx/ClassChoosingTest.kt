package io.github.matthewjones372.lark.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

@Serializable
private data class Repo(val useCache: Boolean)

private interface Inventory

private class CachedInventory : Inventory

private class PlainInventory : Inventory

class ClassChoosingTest {

    @Test
    fun `choosing with no block reads the choice from the class`() {
        val hocon = ConfigFactory.parseString("repo { useCache = true }").resolve()
        val cached: Module = single<Inventory> { CachedInventory() }
        val plain: Module = single<Inventory> { PlainInventory() }

        val app = configOf(hocon) + hocon.choosing<Repo>("repo") { repo -> if (repo.useCache) cached else plain }

        testApp(app) { inventory: Inventory -> inventory }.shouldBeInstanceOf<CachedInventory>()
        testApp(app) { repo: Repo -> repo } shouldBe Repo(useCache = true)
    }
}

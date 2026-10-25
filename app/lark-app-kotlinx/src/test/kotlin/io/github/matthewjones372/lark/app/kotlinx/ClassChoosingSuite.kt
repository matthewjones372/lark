package io.github.matthewjones372.lark.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.resource
import kotlinx.serialization.Serializable

@Serializable
private data class Repo(val useCache: Boolean)

private interface Inventory

private class CachedInventory : Inventory

private class PlainInventory : Inventory

object ClassChoosingSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("choosing with no block reads the choice from the class") { _ ->
            val hocon = ConfigFactory.parseString("repo { useCache = true }").resolve()
            val cached: Module = single<Inventory> { CachedInventory() }
            val plain: Module = single<Inventory> { PlainInventory() }

            val app = configOf(hocon) + hocon.choosing<Repo>("repo") { repo -> if (repo.useCache) cached else plain }

            testApp(app) { inventory: Inventory -> inventory }.shouldBeInstanceOf<CachedInventory>() and
                (testApp(app) { repo: Repo -> repo } shouldBe Repo(useCache = true))
        }
    }
}

package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

private class Config
private class Pool
private class Repo
private class Cache
private class Metrics
private class Server

class ModuleTest {

    @Test
    fun `render draws an edge for every dependency`() {
        val rendered = (single<Config> { Config() } + single { _: Config -> Pool() }).render()

        rendered shouldContain "graph TD"
        rendered shouldContain "Config --> Pool"
    }

    @Test
    fun `a later module replaces an earlier node under the same key`() {
        val module = single<Config> { Config() } + single<Config> { Config() } + single { _: Config -> Pool() }

        val declarations = module.render().lines().filter { it.contains("Config[") }

        withClue("both recipes build a Config, so the graph holds one node and one edge into Pool") {
            declarations.size shouldBe 1
        }
        module.render() shouldContain "Config --> Pool"
    }

    @Test
    fun `a recipe declares up to five dependencies as parameters`() {
        val module = single<Config> { Config() } +
            single { _: Config -> Pool() } +
            single { _: Config, _: Pool -> Repo() } +
            single { _: Config, _: Pool, _: Repo -> Cache() } +
            single { _: Config, _: Pool, _: Repo, _: Cache -> Metrics() } +
            single { _: Config, _: Pool, _: Repo, _: Cache, _: Metrics -> Server() }

        val rendered = module.render()

        rendered.lines().count { it.contains("[") } shouldBe 6
        rendered shouldContain "Metrics --> Server"
        rendered shouldContain "Config --> Server"
    }

    @Test
    fun `a dependency nothing builds is still drawn`() {
        val rendered = single { _: Config -> Pool() }.render()

        withClue("the edge is the reader's clue that Config is the key nothing provides") {
            rendered shouldContain "Config --> Pool"
        }
    }
}

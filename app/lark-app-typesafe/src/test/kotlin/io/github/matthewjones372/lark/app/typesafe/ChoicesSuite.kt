package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.typesafe.config.Config as Hocon

private interface Repo

private class Plain : Repo

private class Caching(val ttl: Duration) : Repo

private data class RepoConf(val useCache: Boolean, val ttl: Duration)

private class Orders(val repo: Repo)

/** A setting that does not configure a repository but chooses between two of them. */
object ChoicesSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private fun repo(): Reading.() -> RepoConf = { RepoConf(boolean("useCache"), duration("ttl")) }

    private fun persistence(conf: Hocon, caching: Module, plain: Module): Module =
        conf.choosing("repo", repo()) { repo -> if (repo.useCache) caching else plain }

    private fun caching(): Module = single { conf: RepoConf -> Caching(conf.ttl) as Repo }

    private fun plain(): Module = single<Repo> { Plain() }

    private fun watched(built: AtomicBoolean): Module = single<Repo> {
        built.set(true)
        Plain()
    }

    private fun hocon(useCache: Boolean): Hocon =
        ConfigFactory.parseString("repo { useCache = $useCache, ttl = 5s }").resolve()

    init {
        test("the setting picks which module the graph holds") { _ ->
            val cached = hocon(useCache = true)

            val app = configOf(cached) + persistence(cached, caching(), plain())

            testApp(app) { repo: Repo -> repo }.shouldBeInstanceOf<Caching> { it.ttl shouldBe 5.seconds }
        }

        test("the module the setting did not pick is not in the graph at all") { _ ->
            val built = AtomicBoolean(false)
            val uncached = hocon(useCache = false)

            val app = configOf(uncached) + persistence(uncached, watched(built), plain())

            testApp(app) { repo: Repo -> repo }.shouldBeInstanceOf<Plain>() and
                withClue("a node lark-app holds is a node lark-app builds, so the unchosen one must be absent") {
                    (built.get() shouldBe false) and (app.render() shouldNotContain "Caching")
                }
        }

        test("the section the choice read is a node, so nothing reads the path twice") { _ ->
            val cached = hocon(useCache = true)

            val app = configOf(cached) + persistence(cached, caching(), plain()) +
                single { repo: Repo -> Orders(repo) }

            testApp(app) { orders: Orders -> orders.repo }.shouldBeInstanceOf<Caching> { it.ttl shouldBe 5.seconds }
        }

        test("a section that will not read refuses the start, naming every fault") { _ ->
            val empty = ConfigFactory.parseString("repo {}").resolve()

            val app = configOf(empty) + persistence(empty, caching(), plain())

            app.use { _: Repo -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused> { error ->
                withClue("one message listing what is wrong beats one deploy per fault") {
                    (error.reason shouldContain "useCache") and (error.reason shouldContain "ttl")
                }
            }
        }

        test("a read that throws on a discarded value names the faults where the choice is written") { _ ->
            val empty = ConfigFactory.parseString("repo {}").resolve()

            shouldThrow<IllegalStateException>({
                empty.choosing("repo", { require(boolean("useCache")) { "useCache" } }) { plain() }
            }) { thrown ->
                withClue("the fault is the thing to fix, not the require it tripped on the way past") {
                    thrown.message.orEmpty() shouldContain "useCache"
                }
            }
        }

        test("a read that throws with no fault recorded throws its own exception, not an empty refusal") { _ ->
            val cached = hocon(useCache = true)

            val off: Reading.() -> Unit = { require(!boolean("useCache")) { "caching is switched off here" } }
            shouldThrow<IllegalArgumentException>({ cached.choosing("repo", off) { plain() } }) { thrown ->
                thrown.message shouldBe "caching is switched off here"
            }
        }

        test("the document the assembly read is the one the graph holds") { _ ->
            val cached = hocon(useCache = true)

            testApp(configOf(cached)) { held: Hocon -> held } shouldBe cached
        }
    }
}

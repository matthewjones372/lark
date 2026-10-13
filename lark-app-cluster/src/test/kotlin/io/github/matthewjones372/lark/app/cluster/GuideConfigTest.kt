package io.github.matthewjones372.lark.app.cluster

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.typesafe.reading
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** The section `docs/cluster.md` shows, read as `cluster(path)` reads it (spec 0096). */
class GuideConfigTest {

    private fun fence(): String {
        val root = System.getProperty("lark.app.cluster.repoRoot")
        withClue("the build must pass -Dlark.app.cluster.repoRoot; see lark-app-cluster/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        val page = File(root!!, "docs/cluster.md").readText()
        val found = Regex("""<!-- cluster-joined -->\s*```hocon\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).find(page)
        withClue("docs/cluster.md must hold a hocon fence marked <!-- cluster-joined -->") { found.shouldNotBeNull() }
        return found!!.groupValues[1]
    }

    @Test
    fun `the guide's section reads without a fault, and joins through its static seeds`() {
        val section = ConfigFactory.parseString(fence()).resolve().getConfig("shop.cluster")
        val read = section.reading { clusterSettings("shop.cluster") }
        withClue(read.leftOrNull()) { read.getOrNull().shouldNotBeNull() }
        val settings = read.getOrNull()

        settings!!.name shouldBe "shop-1"
        settings.port shouldBe 25520
        settings.gossiping.ackWithin shouldBe 600.milliseconds
        settings.whenDowned shouldBe WhenDowned.Exit
        settings.joining().use { it.discovery.seeds().map { seed -> seed.port } shouldContainExactly listOf(25520) }
    }
}

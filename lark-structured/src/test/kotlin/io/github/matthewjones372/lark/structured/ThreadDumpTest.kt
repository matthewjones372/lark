package io.github.matthewjones372.lark.structured

import com.sun.management.HotSpotDiagnosticMXBean
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class ThreadDumpTest {

    // The JSON dump's own spelling: every container names its parent by the same identifier.
    private val container = Regex(""""container": "([^"]+)",\s*"parent": ("[^"]+"|null)""")

    @Test
    fun `a thread dump shows each fork under the scope that opened it`() {
        // The block never runs beside its forks, so the dump is taken from one fork while the other waits.
        val waiting = CountDownLatch(1)
        val dumped = CountDownLatch(1)
        val dump = structured<Nothing, String>("dashboard") {
            val held = async {
                waiting.countDown()
                dumped.await()
                ""
            }
            val dumping = async {
                waiting.await()
                dumpThreads().also { dumped.countDown() }
            }
            awaitAll(held, dumping).second
        }.getOrNull()!!

        val parents = container.findAll(dump).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val scope = parents.keys.single { it.startsWith("dashboard\\/jdk") }
        val nested = parents.keys.single { it.startsWith("dashboard\\/1\\/jdk") }
        withClue(dump) {
            parents[nested] shouldBe scope
            Regex(""""name": "([^"]+)"""").findAll(dump).map { it.groupValues[1] }.toList()
                .shouldContainAll("dashboard\\/1", "dashboard\\/2")
        }
    }

    private fun dumpThreads(): String {
        val file = Files.createTempDirectory("lark-structured").resolve("threads.json")
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON)
        return Files.readString(file)
    }
}

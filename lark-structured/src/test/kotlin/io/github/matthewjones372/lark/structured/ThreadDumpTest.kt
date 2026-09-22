package io.github.matthewjones372.lark.structured

import com.sun.management.HotSpotDiagnosticMXBean
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
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
        val hold = CountDownLatch(1)
        val started = CountDownLatch(1)
        lateinit var dump: String
        structured<Nothing, Unit>("dashboard") {
            val fork = async {
                started.countDown()
                hold.await()
            }
            started.await()
            dump = dumpThreads()
            hold.countDown()
            fork.await()
        }

        val parents = container.findAll(dump).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val scope = parents.keys.single { it.startsWith("dashboard\\/jdk") }
        val nested = parents.keys.single { it.startsWith("dashboard\\/1\\/jdk") }
        withClue(dump) {
            parents[nested] shouldBe scope
            Regex(""""name": "([^"]+)"""").findAll(dump).map { it.groupValues[1] }.toList() shouldContain
                "dashboard\\/1"
        }
    }

    private fun dumpThreads(): String {
        val file = Files.createTempDirectory("lark-structured").resolve("threads.json")
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON)
        return Files.readString(file)
    }
}

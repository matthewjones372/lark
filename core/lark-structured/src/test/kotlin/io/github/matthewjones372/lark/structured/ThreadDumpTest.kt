package io.github.matthewjones372.lark.structured

import arrow.core.raise.either
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
    fun `a thread dump shows each branch under the scope of the call that forked it`() {
        val waiting = CountDownLatch(1)
        val dumped = CountDownLatch(1)
        val dump = either<Nothing, String> {
            parZip({
                waiting.countDown()
                dumped.await()
            }, {
                // A scope of its own inside this branch, so the dump has a nesting to show.
                parZip({
                    waiting.await()
                    dumpThreads().also { dumped.countDown() }
                }, { 0 }) { d, _ -> d }
            }) { _, d -> d }
        }.getOrNull()!!

        val caller = "ThreadDumpTest.a thread dump shows each branch under the scope of the call that forked it"
        val parents = container.findAll(dump).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val outer = parents.keys.single { it.startsWith("$caller\\/jdk") }
        val nested = parents.keys.single { it.startsWith("$caller\\/2\\/jdk") }
        withClue(dump) {
            parents[nested] shouldBe outer
            Regex(""""name": "([^"]+)"""").findAll(dump).map { it.groupValues[1] }.toList()
                .shouldContainAll("$caller\\/1", "$caller\\/2", "$caller\\/2\\/1")
        }
    }

    private fun dumpThreads(): String {
        val file = Files.createTempDirectory("lark-structured").resolve("threads.json")
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON)
        return Files.readString(file)
    }
}

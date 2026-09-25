package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** A measured run's profile, drawn onto its diagram: the stage the time went to is the one marked hot. */
class ProfileTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-profile-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val SETTLE_SECONDS = 20L
    }

    private fun <R : Any> Run<*, R>.profiledOn(backend: StreamBackend): Profile {
        val profiler = Profiler()
        measured(Measured("profiled", profiler, sampleEvery = 1)).run(backend)
            .toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
        return profiler.profile()
    }

    private fun Profile.hottest(): Int = stages.keys.filter { share(it) != null }.maxBy { share(it)!! }

    private fun String.lineOf(stage: String): String = lines().single { it.contains("[\"$stage") }

    @Test
    fun `the mapPar baseline's diagram marks mapPar hottest`() {
        val parallel = Stream.from(1..200)
            .map { it + 1 }
            .mapPar(8) { n ->
                Thread.sleep(1)
                n * 3L
            }
            .runFold(0L) { total, n -> total + n }

        val profile = parallel.profiledOn(PekkoStreams(system))
        val diagram = parallel.render(Layout.Mermaid, profile = profile)

        withClue(diagram) {
            profile.hottest() shouldBe 2
            diagram.lineOf("mapPar(8)") shouldEndWith ":::hot"
            diagram.lineOf("map ·") shouldEndWith ":::cool"
            diagram.lineOf("mapPar(8)") shouldContain "200 out"
        }
    }

    @Test
    fun `a run on forks is profiled the same way, and its text rendering says where the time went`() {
        val chain = Stream.from(1..50)
            .map { it + 1 }
            .map { n ->
                Thread.sleep(1)
                n
            }
            .filter { it % 2 == 0 }
            .runCollect()

        val profile = chain.profiledOn(Forks())
        val text = chain.render(profile = profile)

        withClue(text) {
            profile.hottest() shouldBe 2
            text.lines()[2] shouldContain Regex("""map +ProfileTest\.kt:\d+ +\d+% · """)
            profile.stages.getValue(3).elements shouldBe 25
        }
    }
}

package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.pekko.actor.testkit.typed.javadsl.LoggingTestKit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * What a defect says and who hears it: the log a caller who reads nothing still gets, and the
 * operator, element and build site that ride with the cause.
 */
class DefectTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-defect-test")

        // The comments marking the calls whose lines a build site must name. No line holding one
        // of these constants ends with the marker, so the search below cannot find itself instead.
        private const val ASYNC_SITE = "// mapAsync built here"
        private const val REFUSED_SITE = "// failing mapAsync built here"
        private const val MAP_SITE = "// map built here"
        private const val PAR_SITE = "// mapPar built here"
        private const val PIPE_SITE = "// pipe built here"
        private const val CONCAT_SITE = "// mapConcat built here"
        private const val SEED_SITE = "// conflateWithSeed built here"
    }

    private data class Row(val id: Int)

    private val rows = listOf(Row(1), Row(2))

    private val noLedger = IllegalStateException("no ledger")

    private val ledgerDown = IllegalStateException("ledger down")

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** A ledger that answers null for the second row, where its own type says it will not. */
    @Suppress("UNCHECKED_CAST")
    private fun settle(row: Row): CompletionStage<Row> =
        if (row.id == 2) {
            CompletableFuture.completedFuture<Row?>(null) as CompletionStage<Row>
        } else {
            CompletableFuture.completedFuture(row)
        }

    /** A ledger that refuses the second row by failing its stage rather than by throwing. */
    private fun refuse(row: Row): CompletionStage<Row> =
        if (row.id == 2) {
            CompletableFuture.failedFuture(ledgerDown)
        } else {
            CompletableFuture.completedFuture(row)
        }

    private fun stamp(row: Row): Row = if (row.id == 2) throw noLedger else row

    /** The pipeline the log tests share, folded to a count so nothing holds an element. */
    private fun counted(): Run<Nothing, Int> =
        Stream.from(rows)
            .mapAsync(1) { row -> settle(row) } // mapAsync built here
            .runFold(0) { n, _ -> n + 1 }

    /** The same shape as [counted], for a stage that fails rather than one that completes with null. */
    private fun refused(): Run<Nothing, Int> =
        Stream.from(rows)
            .mapAsync(1) { row -> refuse(row) } // failing mapAsync built here
            .runFold(0) { n, _ -> n + 1 }

    /** The build hands the root over, so the file read here is the one this test was compiled from. */
    private fun source(): File {
        val root = System.getProperty("lark.stream.repoRoot")
        withClue("the build must pass -Dlark.stream.repoRoot; see lark-stream/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "lark-stream/src/test/kotlin/io/github/matthewjones372/lark/stream/DefectTest.kt")
    }

    /** Where a marked call sits, read out of this file rather than written down as a number that moves. */
    private fun siteOf(marker: String): String {
        val index = source().readLines().indexOfFirst { line -> line.trimEnd().endsWith(marker) }
        withClue("no line of ${source().name} ends with `$marker`") { index shouldBeGreaterThan -1 }
        return "${source().name}:${index + 1}"
    }

    @Test
    fun `a defect reaches the system log though nobody reads the stage`() {
        LoggingTestKit.error("mapAsync died on Row(id=2), built at ${siteOf(ASYNC_SITE)}")
            .withCause(NullPointerException::class.java)
            .expect(pekko.typed) { counted().run(pekko.system) }
    }

    @Test
    fun `a null completion dies naming the operator, the element and the build site`() {
        val exit = counted().run(pekko.system).settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause.shouldBeInstanceOf<NullPointerException>()
        died.cause.message shouldContain "mapAsync died on Row(id=2), built at ${siteOf(ASYNC_SITE)}"
    }

    @Test
    fun `a stage that fails keeps its own exception, and the three facts ride with it`() {
        val at = siteOf(REFUSED_SITE)

        val exit = LoggingTestKit.error("mapAsync died on Row(id=2), built at $at")
            .withCause(IllegalStateException::class.java)
            .expect(pekko.typed) { refused().run(pekko.system).settled() }

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        withClue("a stage failed by the caller's own throwable hands back that throwable") {
            died.cause shouldBeSameInstanceAs ledgerDown
        }
        died.cause.suppressed.single().message shouldContain "mapAsync died on Row(id=2), built at $at"
    }

    @Test
    fun `a map that throws keeps its own exception, and the three facts ride with it`() {
        val exit = Stream.from(rows)
            .map { row -> stamp(row) } // map built here
            .runCollect()
            .run(pekko.system)
            .settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        withClue("wrapping the cause would lose the class the caller's own code threw") {
            died.cause shouldBeSameInstanceAs noLedger
        }
        died.cause.suppressed.single().message shouldContain
            "map died on Row(id=2), built at ${siteOf(MAP_SITE)}"
    }

    @Test
    fun `a mapPar body that throws keeps its own exception, and the three facts ride with it`() {
        val exit = Stream.from(rows)
            // The type arguments are written out because a body that only throws names no failure,
            // and the `Stream<Nothing, A>` overload reads its `F` out of the body.
            .mapPar<Nothing, Row, Row>(1) { row -> stamp(row) } // mapPar built here
            .runCollect()
            .run(pekko.system)
            .settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        withClue("a body's own throwable is what the stage is completed with") {
            died.cause shouldBeSameInstanceAs noLedger
        }
        died.cause.suppressed.single().message shouldContain
            "mapPar died on Row(id=2), built at ${siteOf(PAR_SITE)}"
    }

    @Test
    fun `a mapConcat that throws keeps its own exception, and the three facts ride with it`() {
        val at = siteOf(CONCAT_SITE)

        val exit = LoggingTestKit.error("mapConcat died on Row(id=2), built at $at")
            .withCause(IllegalStateException::class.java)
            .expect(pekko.typed) {
                Stream.from(rows)
                    .mapConcat { row -> listOf(stamp(row)) } // mapConcat built here
                    .runCollect()
                    .run(pekko.system)
                    .settled()
            }

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        withClue("wrapping the cause would lose the class the caller's own code threw") {
            died.cause shouldBeSameInstanceAs noLedger
        }
        died.cause.suppressed.single().message shouldContain "mapConcat died on Row(id=2), built at $at"
    }

    /** Which half of the operator sees the second row is the interpreter's business, so both throw. */
    @Test
    fun `a conflateWithSeed that throws keeps its own exception, and the three facts ride with it`() {
        val exit = Stream.from(rows)
            .conflateWithSeed({ row -> stamp(row) }, { _, row -> stamp(row) }) // conflateWithSeed built here
            .runCollect()
            .run(pekko.system)
            .settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause shouldBeSameInstanceAs noLedger
        died.cause.suppressed.single().message shouldContain
            "conflateWithSeed died on Row(id=2), built at ${siteOf(SEED_SITE)}"
    }

    /** A pipe is built where it is written, not where the stream it is spliced into is run. */
    @Test
    fun `a pipe reports the site the pipe itself was built at`() {
        val ledger: Pipe<Nothing, Row, Row> = Pipe.mapAsync(1) { row -> settle(row) } // pipe built here

        val exit = Stream.from(rows).via(ledger).runCollect().run(pekko.system).settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause.message shouldContain "mapAsync died on Row(id=2), built at ${siteOf(PIPE_SITE)}"
    }
}

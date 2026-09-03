package io.github.matthewjones372.lark.stream

import arrow.core.Either
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.pekko.Done
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.javadsl.Sink
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The example in `docs/stream.md`, compiled out of the document and then run
 * beside it: the fence marked `<!-- readme-example -->`, because the marker
 * renders as nothing on the page and survives the heading above it being
 * reworded.
 */
class ReadmeExampleTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("dipper-readme-test")

        private const val MARKER = "<!-- readme-example -->"
    }

    @TempDir
    lateinit var workspace: File

    private val marked = Regex("""$MARKER\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    /** The build hands the root over, so the document read here is the one the repository ships. */
    private fun document(): File {
        val root = System.getProperty("lark.stream.repoRoot")
        withClue("the build must pass -Dlark.stream.repoRoot; see lark-stream/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/stream.md")
    }

    private fun examples(): List<String> =
        marked.findAll(document().readText()).map { match -> match.groupValues[1] }.toList()

    @Test
    fun `the document marks exactly one example to compile`() {
        withClue("${document()} must hold one kotlin fence marked $MARKER") {
            examples().size shouldBe 1
        }
    }

    @Test
    fun `the example the document shows compiles against the library`() {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(examples().single())

        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }

    /** The same pipeline, line for line, run: what the document shows is compiled and also true. */
    @Test
    fun `the pipeline the document shows counts the receipts and diverts the declines`() {
        val counted = ConcurrentLinkedQueue<Declined>()
        val bothDeclines = CountDownLatch(2)
        val declinedSink: Sink<Declined, CompletionStage<Done>> = Sink.foreach { declined ->
            counted.add(declined)
            bothDeclines.countDown()
        }

        val settled: CompletionStage<Exit<IngestError, Int>> =
            Stream.from(rows)
                .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
                .mapAsync(4) { customer -> ledger.settle(customer) }
                .divertLefts(to = declinedSink)
                .runFold(0) { n, _ -> n + 1 }
                .run(system)

        settled.toCompletableFuture().join() shouldBe Exit.Done(3)
        withClue("the diverted branch is a branch of its own and can outlive the run") {
            bothDeclines.await(30, TimeUnit.SECONDS) shouldBe true
        }
        counted.toList() shouldBe listOf(Declined(2), Declined(4))
    }

    private data class Row(val id: Int, val customer: String?)

    private data class Customer(val id: Int, val name: String)

    private data class Receipt(val id: Int)

    private sealed interface IngestError

    private data class NoCustomer(val id: Int) : IngestError

    private data class Declined(val id: Int) : IngestError

    /** The document's ledger, declining the even ids so that the twin runs both branches. */
    private class Ledger {
        fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
            if (customer.id % 2 == 0) {
                CompletableFuture.completedFuture(Either.Left(Declined(customer.id)))
            } else {
                CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
            }
    }

    private val ledger = Ledger()

    private val rows = listOf(
        Row(1, "ada"),
        Row(2, "grace"),
        Row(3, "alan"),
        Row(4, "edsger"),
        Row(5, "barbara"),
    )

    private val system: ClassicActorSystemProvider get() = pekko.system
}

package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
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
 * The examples in `docs/stream.md`, compiled out of the document and then run
 * beside it: the fences marked `<!-- ... -->`, because a marker renders as
 * nothing on the page and survives the heading above it being reworded.
 *
 * The README's Streams section shows the same pipelines, and is held to the same
 * text here, so that one compilation covers both pages.
 */
class ReadmeExampleTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("dipper-readme-test")

        private const val MARKER = "<!-- readme-example -->"

        /** The three fences of the before-and-after: the fixtures, and the pipeline written each way. */
        private const val FIXTURES = "<!-- example-fixtures -->"
        private const val BEFORE = "<!-- before-example -->"
        private const val AFTER = "<!-- after-example -->"

        /** The four lines that say what absence is, on both pages. */
        private const val MISSING = "<!-- missing-example -->"

        /** The one that only the document carries, since the README sends the reader there for it. */
        private const val BLOCKING = "<!-- mappar-example -->"

        private val shownOnBothPages = listOf(MARKER, FIXTURES, BEFORE, AFTER, MISSING)
    }

    @TempDir
    lateinit var workspace: File

    private fun marked(marker: String) = Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    /** The build hands the root over, so the pages read here are the ones the repository ships. */
    private fun page(name: String): File {
        val root = System.getProperty("lark.stream.repoRoot")
        withClue("the build must pass -Dlark.stream.repoRoot; see lark-stream/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, name)
    }

    private fun document(): File = page("docs/stream.md")

    private fun readme(): File = page("README.md")

    private fun examples(page: File, marker: String): List<String> =
        marked(marker).findAll(page.readText()).map { match -> match.groupValues[1] }.toList()

    private fun only(page: File, marker: String): String {
        withClue("$page must hold one kotlin fence marked $marker") {
            examples(page, marker).size shouldBe 1
        }
        return examples(page, marker).single()
    }

    private fun compiles(source: String) {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)

        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }

    @Test
    fun `the document marks one fence for each example it compiles`() {
        (shownOnBothPages + BLOCKING).forEach { marker -> only(document(), marker) }
    }

    @Test
    fun `the README's Streams section shows the examples the document compiles`() {
        shownOnBothPages.forEach { marker ->
            withClue("the two pages must show the same $marker fence, so that compiling it covers both") {
                only(readme(), marker) shouldBe only(document(), marker)
            }
        }
    }

    @Test
    fun `the example the document shows compiles against the library`() {
        compiles(only(document(), MARKER))
    }

    /** Both halves of the before-and-after, each on the fixtures the section states once. */
    @Test
    fun `the pipeline written each way compiles against what it is written on`() {
        val fixtures = only(document(), FIXTURES)

        compiles("$fixtures\n\n${only(document(), BEFORE)}")
        compiles("$fixtures\n\n${only(document(), AFTER)}")
    }

    @Test
    fun `the builders that name an absence compile as the document shows them`() {
        compiles(only(document(), MISSING))
    }

    @Test
    fun `the blocking body the document shows compiles`() {
        compiles(only(document(), BLOCKING))
    }

    /** The same pipeline, line for line, run: what the document shows is compiled and also true. */
    @Test
    fun `the pipeline the document shows sends the receipts and diverts the declines`() {
        val declines = ConcurrentLinkedQueue<Declined>()
        val bothDeclines = CountDownLatch(2)
        val declinedSink = Sink.foreach<Declined> { declined ->
            declines.add(declined)
            bothDeclines.countDown()
        }
        val receipts = ConcurrentLinkedQueue<Receipt>()
        val receiptSink = Sink.foreach<Receipt> { receipt -> receipts.add(receipt) }

        val settled: Either<IngestError, Done> = either {
            awaitExit(
                Stream.from(rows)
                    .mapOrFail { row -> Customer(row.id, row.customer ?: raise(NoCustomer(row.id))) }
                    .mapAsync(4) { customer -> ledger.settle(customer) }
                    .divertLefts(to = declinedSink)
                    .runWith(receiptSink)
                    .run(system),
            )
        }

        settled shouldBe Done.getInstance().right()
        withClue("the run ends when its own sink does, so every receipt has been sent by now") {
            receipts.toList() shouldBe listOf(Receipt(1), Receipt(3), Receipt(5))
        }
        withClue("the diverted branch is a branch of its own and can outlive the run") {
            bothDeclines.await(30, TimeUnit.SECONDS) shouldBe true
        }
        declines.toList() shouldBe listOf(Declined(2), Declined(4))
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

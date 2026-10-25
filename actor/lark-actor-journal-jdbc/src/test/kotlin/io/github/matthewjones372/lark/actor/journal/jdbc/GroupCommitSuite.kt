package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeEmpty
import io.github.matthewjones372.lark.junit.shouldBeLessThan
import io.github.matthewjones372.lark.junit.shouldThrow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Spec 0108: appends from many ids share a commit, and each still answers as it would have alone. */
object GroupCommitSuite : Suite() {
    init {
        test("two hundred appends at once to two hundred ids all land, in fewer commits than appends") {
            val source = CountedCommits(Postgres.fresh())
            val journal = JdbcJournal(source, groupCommit = GroupCommit())
            val ids = (1..200).map { PersistenceId("till", "t-$it") }

            val appended = Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                ids.map { id -> threads.submit(Callable { journal.append(id, 0, listOf(byteArrayOf(1))) }) }
                    .forAll { it.get().isRight() shouldBe true }
            }

            appended and
                ids.forAll { id -> journal.read(id).size shouldBe 1 } and
                (source.commits.get() shouldBeLessThan ids.size)
        }

        test("a conflicting append in a group is answered alone, and the rest of the group commits") {
            val held = CountDownLatch(1)
            val source = CountedCommits(Postgres.fresh(), holdFirst = held)
            val journal = JdbcJournal(source, groupCommit = GroupCommit(committers = 1))
            val first = appendLater { journal.append(PersistenceId("till", "a"), 0, listOf(byteArrayOf(1))) }
            val started = source.firstCommitStarted.await(10, TimeUnit.SECONDS) shouldBe true

            // Queued while the first group commits, so they are the next group.
            val fine = appendLater { journal.append(PersistenceId("till", "b"), 0, listOf(byteArrayOf(1))) }
            val stale = appendLater { journal.append(PersistenceId("till", "c"), 5, listOf(byteArrayOf(1))) }
            val alsoFine = appendLater { journal.append(PersistenceId("till", "d"), 0, listOf(byteArrayOf(1))) }
            waitUntil { journal.queued() == 3 }
            held.countDown()

            started and
                (first.get(10, TimeUnit.SECONDS).getOrNull() shouldBe 1L) and
                (fine.get(10, TimeUnit.SECONDS).getOrNull() shouldBe 1L) and
                (stale.get(10, TimeUnit.SECONDS).leftOrNull()?.actual shouldBe 0L) and
                (alsoFine.get(10, TimeUnit.SECONDS).getOrNull() shouldBe 1L) and
                (source.commits.get() shouldBe 2)
        }

        test("a commit that fails fails every append in its group, and none of them is written") {
            val held = CountDownLatch(1)
            val source = CountedCommits(Postgres.fresh(), holdFirst = held, failSecond = true)
            val journal = JdbcJournal(source, groupCommit = GroupCommit(committers = 1))
            val first = appendLater { journal.append(PersistenceId("till", "a"), 0, listOf(byteArrayOf(1))) }
            val started = source.firstCommitStarted.await(10, TimeUnit.SECONDS) shouldBe true

            val lost = listOf("b", "c").map { id ->
                appendLater { journal.append(PersistenceId("till", id), 0, listOf(byteArrayOf(1))) }
            }
            waitUntil { journal.queued() == 2 }
            held.countDown()

            started and
                (first.get(10, TimeUnit.SECONDS).getOrNull() shouldBe 1L) and
                lost.forAll { append ->
                    shouldThrow<SQLException> {
                        try {
                            append.get(10, TimeUnit.SECONDS)
                        } catch (failed: java.util.concurrent.ExecutionException) {
                            throw checkNotNull(failed.cause)
                        }
                    }
                } and
                journal.read(PersistenceId("till", "b")).shouldBeEmpty() and
                journal.read(PersistenceId("till", "c")).shouldBeEmpty()
        }
    }

    @Suppress("TooGenericExceptionCaught") // The append's own failure is what the test asserts on.
    private fun <A> appendLater(append: () -> A): CompletableFuture<A> {
        val done = CompletableFuture<A>()
        Thread.ofVirtual().start {
            try {
                done.complete(append())
            } catch (failed: Throwable) {
                done.completeExceptionally(failed)
            }
        }
        return done
    }

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < until) { "never came true" }
            Thread.sleep(5)
        }
    }
}

/**
 * [real], counting the commits made through it. With [holdFirst], the first commit waits for it to open; with
 * [failSecond], the second commit throws instead of committing.
 */
private class CountedCommits(
    private val real: DataSource,
    private val holdFirst: CountDownLatch? = null,
    private val failSecond: Boolean = false,
) : DataSource by real {
    val commits = AtomicInteger()
    val firstCommitStarted = CountDownLatch(1)

    override fun getConnection(): Connection {
        val connection = real.connection
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit") {
                val nth = commits.incrementAndGet()
                if (nth == 1) {
                    firstCommitStarted.countDown()
                    holdFirst?.await(10, TimeUnit.SECONDS)
                }
                if (nth == 2 && failSecond) throw SQLException("the database went away mid-commit", "08006")
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        } as Connection
    }
}

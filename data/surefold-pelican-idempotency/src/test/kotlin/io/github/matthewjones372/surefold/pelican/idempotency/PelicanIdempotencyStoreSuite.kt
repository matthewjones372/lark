package io.github.matthewjones372.surefold.pelican.idempotency

import io.github.matthewjones372.pelican.IdempotencyScope
import io.github.matthewjones372.pelican.Reservation
import io.github.matthewjones372.pelican.StoredResponse
import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.idempotency.PostgresIdempotency
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource

/** Pelican's own idempotency store cases, held against Postgres through the adapter (spec 0230). */
object PelicanIdempotencyStoreSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")
    private val scope = IdempotencyScope(operation = "placeOrder", subject = "ada", key = "k-1")
    private val lease = Duration.ofSeconds(30)
    private val ttl = Duration.ofHours(24)
    private val created = StoredResponse(201, listOf("Location" to "/orders/1"), "application/json", "{}".toByteArray())

    init {
        test("fifty nodes reserving one key at once give one Reserved, and the rest see it in flight") {
            val source = database()
            val answers = ConcurrentLinkedQueue<Reservation>()
            val ready = CountDownLatch(50)
            (1..50).map {
                val node = PelicanIdempotencyStore(PostgresIdempotency(source))
                Thread.ofVirtual().start {
                    ready.countDown()
                    ready.await()
                    answers += node.reserve(scope, "fp-a", lease)
                }
            }.forEach { it.join() }
            answers.count { it == Reservation.Reserved } shouldBe 1
            answers.count { it == Reservation.InFlight } shouldBe 49
        }

        test("a completed key replays its response, media type and all, to every node") {
            val source = database()
            val first = PelicanIdempotencyStore(PostgresIdempotency(source))
            val second = PelicanIdempotencyStore(PostgresIdempotency(source))
            first.reserve(scope, "fp-a", lease) shouldBe Reservation.Reserved
            first.complete(scope, created, ttl)
            listOf(first.reserve(scope, "fp-a", lease), second.reserve(scope, "fp-a", lease), second.lookup(scope))
                .forEach { answer ->
                    val response = (answer as Reservation.Done).response
                    response.status shouldBe 201
                    response.headers shouldBe listOf("Location" to "/orders/1")
                    response.mediaType shouldBe "application/json"
                    String(response.body) shouldBe "{}"
                }
        }

        test("a response with no media type replays with none") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            store.reserve(scope, "fp-a", lease)
            store.complete(scope, StoredResponse(204, emptyList(), null, ByteArray(0)), ttl)
            (store.lookup(scope) as Reservation.Done).response.mediaType shouldBe null
        }

        test("the same key for a different request is a mismatch whether or not the first has finished") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            store.reserve(scope, "fp-a", lease)
            store.reserve(scope, "fp-b", lease) shouldBe Reservation.Mismatch
            store.complete(scope, created, ttl)
            store.reserve(scope, "fp-b", lease) shouldBe Reservation.Mismatch
        }

        test("a key is scoped to its operation and its caller") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            store.reserve(scope, "fp-a", lease)
            store.reserve(scope.copy(subject = "grace"), "fp-a", lease) shouldBe Reservation.Reserved
            store.reserve(scope.copy(subject = null), "fp-a", lease) shouldBe Reservation.Reserved
            store.reserve(scope.copy(operation = "cancelOrder"), "fp-a", lease) shouldBe Reservation.Reserved
        }

        test("a released key runs again, and a release leaves a completed response alone") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            store.reserve(scope, "fp-a", lease)
            store.release(scope)
            store.lookup(scope) shouldBe null
            store.reserve(scope, "fp-b", lease) shouldBe Reservation.Reserved
            store.complete(scope, created, ttl)
            store.release(scope)
            (store.lookup(scope) is Reservation.Done) shouldBe true
        }

        test("an unfinished reservation is given up after its lease, and a response kept for its ttl") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            val time = TestClock(noon)
            clock.locally(time) {
                store.reserve(scope, "fp-a", lease)
                store.lookup(scope) shouldBe Reservation.InFlight
                time.setTime(noon + lease)
                store.lookup(scope) shouldBe null
                store.reserve(scope, "fp-a", lease) shouldBe Reservation.Reserved
                store.complete(scope, created, ttl)
                time.setTime(noon + lease + ttl.minusSeconds(1))
                (store.lookup(scope) is Reservation.Done) shouldBe true
                time.setTime(noon + lease + ttl)
                store.lookup(scope) shouldBe null
            }
        }

        test("a node whose lease was taken over completes nothing over the new holder's answer") {
            val source = database()
            val dead = PelicanIdempotencyStore(PostgresIdempotency(source))
            val live = PelicanIdempotencyStore(PostgresIdempotency(source))
            val time = TestClock(noon)
            clock.locally(time) {
                dead.reserve(scope, "fp-a", lease)
                time.setTime(noon + lease)
                live.reserve(scope, "fp-a", lease) shouldBe Reservation.Reserved
                dead.complete(scope, StoredResponse(500, emptyList(), null, ByteArray(0)), ttl)
                live.lookup(scope) shouldBe Reservation.InFlight
                live.complete(scope, created, ttl)
                (live.lookup(scope) as Reservation.Done).response.status shouldBe 201
            }
        }

        test("a key surefold-idempotency could not hold as it is, too long or blank, is kept by its hash") {
            val store = PelicanIdempotencyStore(PostgresIdempotency(database()))
            listOf("k".repeat(1000), " ").forEach { key ->
                store.reserve(scope.copy(key = key), "fp-a", lease) shouldBe Reservation.Reserved
                store.reserve(scope.copy(key = key), "fp-a", lease) shouldBe Reservation.InFlight
            }
            store.reserve(scope.copy(key = "k".repeat(1001)), "fp-a", lease) shouldBe Reservation.Reserved
        }
    }

    private fun database(): DataSource = Postgres.empty().also { it.migrate("surefold/idempotency/postgres.sql") }
}

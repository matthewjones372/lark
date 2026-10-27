package io.github.matthewjones372.surefold.bookings

import io.github.matthewjones372.surefold.auth.PostgresRoles
import io.github.matthewjones372.surefold.auth.PostgresSessions
import io.github.matthewjones372.surefold.auth.Secret
import io.github.matthewjones372.surefold.auth.UserId
import io.github.matthewjones372.surefold.files.InMemoryFiles
import io.github.matthewjones372.surefold.idempotency.PostgresIdempotency
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.JobWorker
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.tenancy.RlsDatabase
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Sessions, tenancy, jobs, idempotency and files together, on Postgres with row-level security (spec 0229). */
object BookingsSuite : Suite() {
    private val at = Instant.parse("2026-11-01T19:30:00Z")
    private val table = BookingRequest("Table for two", listOf(Slot("court-1", at, 60.minutes)))
    private val pdf = "%PDF-1.7 ticket".toByteArray()

    init {
        test("a booking retried with its key is made once, and confirmed once by the worker as its tenant") {
            val venue = Venue("acme")
            val staff = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            val request = table
            val first = venue.bookings.book(staff, "k-1", request)
            first.status shouldBe 201
            venue.bookings.book(staff, "k-1", request) shouldBe first
            venue.bookings.book(staff, "k-1", request.copy(name = "Table for six")).status shouldBe 422
            val mailer = RecordingMailer(expected = 1)
            JobWorker(venue.db.system, confirmations(venue.tenants, mailer), JobSettings(poll = 50.milliseconds))
                .start()
                .use { _ -> mailer.await() shouldBe true }
            mailer.sent.toList() shouldBe listOf("acme: Table for two")
            venue.bookings.list(staff).body.lines().size shouldBe 1
        }

        test("another tenant's session sees none of a booking, cannot attach to it, and cannot link its ticket") {
            val venue = Venue("acme", "globex")
            val acme = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            val globex = venue.signIn("globex", BookingService.CREATE, BookingService.VIEW)
            val id = venue.bookings.book(acme, "k-1", table).body.substringAfter(' ')
                .toLong()
            venue.bookings.attachTicket(acme, id, pdf.inputStream(), pdf.size.toLong()).status shouldBe 201
            venue.bookings.ticketLink(acme, id).status shouldBe 200
            venue.bookings.list(globex) shouldBe Answer(200, "")
            venue.bookings.attachTicket(globex, id, pdf.inputStream(), pdf.size.toLong()) shouldBe
                Answer(404, "no booking $id")
            venue.bookings.ticketLink(globex, id) shouldBe Answer(404, "no booking $id")
            // The same idempotency key in another tenant is another booking, not acme's replayed.
            venue.bookings.book(globex, "k-1", table).body shouldBe "booking ${id + 1}"
        }

        test("a session without the permission is refused, and no session at all is told to sign in") {
            val venue = Venue("acme")
            val viewer = venue.signIn("acme", BookingService.VIEW)
            venue.bookings.book(viewer, "k-1", table) shouldBe
                Answer(403, "not allowed to bookings:create")
            venue.bookings.list(viewer).status shouldBe 200
            venue.bookings.list(Secret("not-a-token")) shouldBe Answer(401, "sign in")
            venue.bookings.addCourt(viewer, Court("court-9")) shouldBe Answer(403, "not allowed to bookings:manage")
        }

        test("two back-to-back slots are one booking, and a slot that clashes makes none of its booking") {
            val venue = Venue("acme")
            val member = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            val evening = listOf(Slot("court-2", at, 60.minutes), Slot("court-2", at.plusSeconds(HOUR), 60.minutes))
            venue.bookings.book(member, "k-1", BookingRequest("Evening hit", evening)).status shouldBe 201

            // Court 1 is free at 20:00, but court 2 is not: neither is held, and court 1 can still be booked.
            val clashing =
                listOf(Slot("court-1", at.plusSeconds(HALF_HOUR), 60.minutes), Slot("court-2", at, 30.minutes))
            venue.bookings.book(member, "k-2", BookingRequest("Doubles", clashing)) shouldBe
                Answer(409, "a slot is already taken")
            venue.bookings.book(member, "k-3", BookingRequest("Singles", clashing.take(1))).status shouldBe 201
            venue.bookings.list(member).body.lines().size shouldBe 2
            // Ending as the next begins is no clash.
            val after = Slot("court-2", at.plusSeconds(2 * HOUR), 60.minutes)
            venue.bookings.book(member, "k-4", BookingRequest("Late", listOf(after))).status shouldBe 201
        }

        test("the same court at the same time in another venue is another court") {
            val venue = Venue("acme", "globex")
            val acme = venue.signIn("acme", BookingService.CREATE)
            val globex = venue.signIn("globex", BookingService.CREATE)
            venue.bookings.book(acme, "k-1", table).status shouldBe 201
            venue.bookings.book(globex, "k-1", table).status shouldBe 201
        }

        test("a member at their limit for a day is refused another slot that day, and booked the next") {
            val venue = Venue("acme")
            venue.limits("acme", Limits(perDay = 2, perWeek = 5, ZoneId.of("Europe/London")))
            val member = venue.signIn("acme", BookingService.CREATE)
            val monday = Instant.parse("2026-11-02T18:00:00Z")
            val two = listOf(Slot("court-1", monday, 60.minutes), Slot("court-1", monday.plusSeconds(HOUR), 60.minutes))
            venue.bookings.book(member, "k-1", BookingRequest("Evening", two)).status shouldBe 201
            venue.bookings.book(member, "k-2", one("court-2", monday)) shouldBe
                Answer(422, "2 slots a day, and 2 are booked on 2026-11-02")
            venue.bookings.book(member, "k-3", one("court-2", monday.plusSeconds(DAY))).status shouldBe 201
            // Another member's share is their own.
            val other = venue.signIn("acme", BookingService.CREATE)
            venue.bookings.book(other, "k-4", one("court-2", monday.plusSeconds(HOUR))).status shouldBe 201
        }

        test("a member at their limit for a week is refused on any day of it, and booked the week after") {
            val venue = Venue("acme")
            venue.limits("acme", Limits(perDay = 2, perWeek = 3, ZoneId.of("Europe/London")))
            val member = venue.signIn("acme", BookingService.CREATE)
            val monday = Instant.parse("2026-11-02T18:00:00Z")
            (0L..2L).forEach { day ->
                venue.bookings.book(member, "k-$day", one("court-1", monday.plusSeconds(day * DAY))).status shouldBe 201
            }
            venue.bookings.book(member, "k-6", one("court-1", monday.plusSeconds(6 * DAY))) shouldBe
                Answer(422, "3 slots a week, and 3 are booked in the week of 2026-11-02")
            venue.bookings.book(member, "k-7", one("court-1", monday.plusSeconds(7 * DAY))).status shouldBe 201
        }

        test("two bookings a member sends at once that together pass the limit are not both made") {
            val venue = Venue("acme")
            venue.limits("acme", Limits(perDay = 1, perWeek = 5, ZoneId.of("Europe/London")))
            val member = venue.signIn("acme", BookingService.CREATE)
            val monday = Instant.parse("2026-11-02T18:00:00Z")
            val start = CountDownLatch(1)
            val sent = listOf("court-1", "court-2").map { court ->
                Thread.ofVirtual().start {
                    start.await()
                    statuses += venue.bookings.book(member, court, one(court, monday)).status
                }
            }
            start.countDown()
            sent.forEach { it.join() }
            statuses.sorted() shouldBe listOf(201, 422)
            statuses.clear()
        }

        test("a booking with no slot, an empty slot or a court the venue has not got is refused") {
            val venue = Venue("acme")
            val member = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            venue.bookings.book(member, "k-1", BookingRequest("Nothing", emptyList())) shouldBe
                Answer(422, "a booking needs a slot")
            venue.bookings.book(member, "k-2", BookingRequest("Blink", listOf(Slot("court-1", at, 0.minutes)))) shouldBe
                Answer(422, "a slot needs a length")
            venue.bookings.book(member, "k-3", BookingRequest("Lost", listOf(Slot("court-7", at, 60.minutes)))) shouldBe
                Answer(422, "no such court")
            venue.bookings.list(member) shouldBe Answer(200, "")
        }
    }

    /**
     * A service on a fresh database, whose requests run as the application's role that the policies bind, and where
     * each of [venues] has courts 1 and 2.
     */
    private class Venue(vararg venues: String) {
        val db = RlsDatabase(bookings = false).apply {
            Schema.changelogs.forEach(::apply)
            // Every table to both roles: the policies, not the grants, keep tenants apart.
            execute("grant select, insert, update, delete on all tables in schema public to $ROLES")
            execute("grant usage on all sequences in schema public to $ROLES")
        }
        val tenants = TenantTransactions(app = db.app, system = db.system)
        private val roles = PostgresRoles(db.app)
        private val sessions = PostgresSessions(db.app, roles = roles)
        val bookings = BookingService(tenants, sessions, PostgresIdempotency(db.app), InMemoryFiles())
        private var users = 0

        init {
            venues.forEach { tenant ->
                val manager = signIn(tenant, BookingService.MANAGE)
                listOf("court-1", "court-2").forEach { bookings.addCourt(manager, Court(it)).status shouldBe 201 }
            }
        }

        /** Sets [tenant]'s [limits], as its manager. */
        fun limits(tenant: String, limits: Limits) {
            bookings.setLimits(signIn(tenant, BookingService.MANAGE), limits).status shouldBe 200
        }

        /** A new member of [tenant]'s staff, with a role allowing [permissions], signed in. */
        fun signIn(tenant: String, vararg permissions: String): Secret {
            val user = UserId("user-${++users}")
            val role = "$tenant-${permissions.joinToString("+")}"
            roles.grant(user, tenant, role)
            permissions.forEach { roles.allow(role, it) }
            return sessions.create(user, tenant).token
        }
    }

    private const val ROLES = "surefold_app, surefold_system"
    private const val HOUR = 3_600L
    private const val HALF_HOUR = 1_800L
    private const val DAY = 86_400L

    private val statuses = ConcurrentLinkedQueue<Int>()

    /** A booking of [court] for an hour from [at]. */
    private fun one(court: String, at: Instant) = BookingRequest("Hit", listOf(Slot(court, at, 60.minutes)))

    private class RecordingMailer(expected: Int) : Mailer {
        val sent = ConcurrentLinkedQueue<String>()
        private val done = CountDownLatch(expected)

        override fun confirm(tenant: String, booking: Booking): Boolean {
            sent += "$tenant: ${booking.name}"
            done.countDown()
            return true
        }

        fun await(): Boolean = done.await(30, TimeUnit.SECONDS)
    }
}

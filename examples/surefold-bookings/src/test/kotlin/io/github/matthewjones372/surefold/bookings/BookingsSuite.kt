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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** Sessions, tenancy, jobs, idempotency and files together, on Postgres with row-level security (spec 0229). */
object BookingsSuite : Suite() {
    private val at = Instant.parse("2026-11-01T19:30:00Z")
    private val pdf = "%PDF-1.7 ticket".toByteArray()

    init {
        test("a booking retried with its key is made once, and confirmed once by the worker as its tenant") {
            val venue = Venue()
            val staff = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            val request = BookingRequest("Table for two", at)
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
            val venue = Venue()
            val acme = venue.signIn("acme", BookingService.CREATE, BookingService.VIEW)
            val globex = venue.signIn("globex", BookingService.CREATE, BookingService.VIEW)
            val id = venue.bookings.book(acme, "k-1", BookingRequest("Table for two", at)).body.substringAfter(' ')
                .toLong()
            venue.bookings.attachTicket(acme, id, pdf.inputStream(), pdf.size.toLong()).status shouldBe 201
            venue.bookings.ticketLink(acme, id).status shouldBe 200
            venue.bookings.list(globex) shouldBe Answer(200, "")
            venue.bookings.attachTicket(globex, id, pdf.inputStream(), pdf.size.toLong()) shouldBe
                Answer(404, "no booking $id")
            venue.bookings.ticketLink(globex, id) shouldBe Answer(404, "no booking $id")
            // The same idempotency key in another tenant is another booking, not acme's replayed.
            venue.bookings.book(globex, "k-1", BookingRequest("Table for two", at)).body shouldBe "booking ${id + 1}"
        }

        test("a session without the permission is refused, and no session at all is told to sign in") {
            val venue = Venue()
            val viewer = venue.signIn("acme", BookingService.VIEW)
            venue.bookings.book(viewer, "k-1", BookingRequest("Table for two", at)) shouldBe
                Answer(403, "not allowed to bookings:create")
            venue.bookings.list(viewer).status shouldBe 200
            venue.bookings.list(Secret("not-a-token")) shouldBe Answer(401, "sign in")
        }
    }

    /** A service on a fresh database, whose requests run as the application's role that the policies bind. */
    private class Venue {
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

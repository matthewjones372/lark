package io.github.matthewjones372.surefold.bookings

import io.github.matthewjones372.surefold.auth.Principal
import io.github.matthewjones372.surefold.auth.Secret
import io.github.matthewjones372.surefold.auth.Sessions
import io.github.matthewjones372.surefold.fail.fold
import io.github.matthewjones372.surefold.files.Files
import io.github.matthewjones372.surefold.files.TenantPrefix
import io.github.matthewjones372.surefold.idempotency.IdempotencyKey
import io.github.matthewjones372.surefold.idempotency.IdempotencyStore
import io.github.matthewjones372.surefold.idempotency.Reservation
import io.github.matthewjones372.surefold.idempotency.StoredResponse
import io.github.matthewjones372.surefold.jobs.Jobs
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import java.io.InputStream
import java.sql.SQLException
import kotlin.time.Duration.Companion.minutes

/** What an endpoint answers: a status and a body, as the HTTP layer would send them. */
data class Answer(val status: Int, val body: String)

/**
 * Bookings for many venues, each a tenant. Every operation starts from a session token: the session's tenant is the
 * only tenant this ever binds, and its permissions decide what the caller may do. The HTTP layer, Pelican's, reads
 * the token from the cookie and the key from the `Idempotency-Key` header, and sends back what this answers.
 */
class BookingService(
    private val tenants: TenantTransactions,
    private val sessions: Sessions,
    private val keys: IdempotencyStore,
    private val files: Files,
) {
    /** Makes a booking once per [idempotencyKey], however often the client retries, and confirms it later. */
    fun book(token: Secret, idempotencyKey: String, request: BookingRequest): Answer =
        signedIn(token, CREATE) { tenant -> booked(tenant, idempotencyKey, request) }

    private fun booked(tenant: String, idempotencyKey: String, request: BookingRequest): Answer {
        val key = IdempotencyKey(scope = tenant, key = idempotencyKey)
        return when (val reservation = keys.reserve(key, fingerprint = "${request.name}|${request.startsAt}")) {
            is Reservation.Reserved -> {
                val answer = try {
                    made(tenant, request)
                } catch (failed: SQLException) {
                    keys.release(key, reservation.token)
                    throw failed
                }
                val stored = StoredResponse(answer.status, emptyList(), answer.body.toByteArray())
                keys.complete(key, reservation.token, stored)
                answer
            }

            is Reservation.Replay -> Answer(reservation.response.status, reservation.response.body.decodeToString())

            Reservation.InProgress -> Answer(CONFLICT, "this booking is being made")

            Reservation.Mismatch -> Answer(UNPROCESSABLE, "this key was used for another booking")
        }
    }

    /** The caller's tenant's bookings, one a line. */
    fun list(token: Secret): Answer = signedIn(token, VIEW) { tenant ->
        val bookings = tenants.transaction(TenantId(tenant)) { Booking.all(it) }
        Answer(OK, bookings.joinToString("\n") { "${it.id}: ${it.name} at ${it.startsAt}" })
    }

    /** Stores [pdf] as [bookingId]'s ticket, under the tenant's prefix; not found unless the tenant can see it. */
    fun attachTicket(token: Secret, bookingId: Long, pdf: InputStream, size: Long): Answer =
        signedIn(token, CREATE) { tenant ->
            if (!visible(tenant, bookingId)) return@signedIn notFound(bookingId)
            val key = ticket(tenant, bookingId)
            files.put(key, pdf, size, "application/pdf").fold(
                { Answer(BAD_GATEWAY, "the ticket could not be stored: $it") },
                { Answer(CREATED, it.etag) },
            )
        }

    /** A link to [bookingId]'s ticket the customer's browser can open for ten minutes. */
    fun ticketLink(token: Secret, bookingId: Long): Answer = signedIn(token, VIEW) { tenant ->
        if (!visible(tenant, bookingId)) return@signedIn notFound(bookingId)
        val key = ticket(tenant, bookingId)
        files.head(key).fold(
            { notFound(bookingId) },
            { _ ->
                files.presignGet(key, 10.minutes)
                    .fold({ failed -> Answer(BAD_GATEWAY, "$failed") }, { link -> Answer(OK, "${link.url}") })
            },
        )
    }

    /** The booking and its confirmation in one transaction, as [tenant]: both commit, or neither does. */
    private fun made(tenant: String, request: BookingRequest): Answer = tenants.transaction(TenantId(tenant)) {
        val id = Booking.insert(it, request)
        Jobs.enqueue(it, SendConfirmation, Confirmation(id), dedupeKey = "confirm-$id", tenant = tenant)
        Answer(CREATED, "booking $id")
    }

    private fun visible(tenant: String, bookingId: Long): Boolean =
        tenants.transaction(TenantId(tenant)) { Booking.find(it, bookingId) } != null

    // Built from the tenant and a number, so a key always parses.
    private fun ticket(tenant: String, bookingId: Long) =
        TenantPrefix(tenant).key("tickets/$bookingId.pdf").fold({ error("$it") }, { it })

    private fun notFound(bookingId: Long) = Answer(NOT_FOUND, "no booking $bookingId")

    private fun signedIn(token: Secret, permission: String, block: (tenant: String) -> Answer): Answer {
        val principal: Principal = sessions.resolve(token) ?: return Answer(UNAUTHORIZED, "sign in")
        val tenant = principal.tenant
        return when {
            tenant == null || !principal.can(permission) -> Answer(FORBIDDEN, "not allowed to $permission")
            else -> block(tenant)
        }
    }

    companion object {
        const val CREATE = "bookings:create"
        const val VIEW = "bookings:view"

        private const val OK = 200
        private const val CREATED = 201
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val UNPROCESSABLE = 422
        private const val BAD_GATEWAY = 502
    }
}

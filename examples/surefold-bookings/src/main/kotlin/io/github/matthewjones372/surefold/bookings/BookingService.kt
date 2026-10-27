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
    /**
     * Makes a booking of every slot in [request] once per [idempotencyKey], however often the client retries, and
     * confirms it later. A slot that clashes with one already held is 409 and makes none of them; the key is released,
     * so the same key may try again once the court is free.
     */
    fun book(token: Secret, idempotencyKey: String, request: BookingRequest): Answer =
        signedIn(token, CREATE) { tenant ->
            refused(request) ?: booked(tenant, user.value, idempotencyKey, request)
        }

    /**
     * Holds every slot of [request] for an event or a party, once per [idempotencyKey], as a booking does: a clash is
     * 409 and holds nothing. Staff only; no member's limit counts it, the host's included.
     */
    fun host(token: Secret, idempotencyKey: String, request: EventRequest): Answer = signedIn(token, HOST) { tenant ->
        if (request.guests < 1) {
            Answer(UNPROCESSABLE, "an event needs a guest")
        } else {
            refused(request.booking) ?: booked(tenant, user.value, idempotencyKey, request.booking, request.guests)
        }
    }

    /** Sets how many slots each member of the caller's venue may hold in a day and a week. */
    fun setLimits(token: Secret, limits: Limits): Answer = signedIn(token, MANAGE) { tenant ->
        tenants.transaction(TenantId(tenant)) { it.setLimits(limits) }
        Answer(OK, "${limits.perDay} a day, ${limits.perWeek} a week, in ${limits.zone}")
    }

    /** Adds [court] to the caller's venue, for its bookings to name. */
    fun addCourt(token: Secret, court: Court): Answer = signedIn(token, MANAGE) { tenant ->
        val added = tenants.transaction(TenantId(tenant)) { it.addCourt(court) }
        if (added) Answer(CREATED, "court ${court.name}") else Answer(OK, "court ${court.name}")
    }

    /** What is wrong with [request] before any database sees it, or null. */
    private fun refused(request: BookingRequest): Answer? = when {
        request.slots.isEmpty() -> Answer(UNPROCESSABLE, "a booking needs a slot")
        request.slots.any { !it.length.isPositive() } -> Answer(UNPROCESSABLE, "a slot needs a length")
        else -> null
    }

    private fun booked(
        tenant: String,
        member: String,
        idempotencyKey: String,
        request: BookingRequest,
        guests: Int? = null,
    ): Answer {
        val key = IdempotencyKey(scope = tenant, key = idempotencyKey)
        val fingerprint = guests?.let { "event for $it|${request.fingerprint}" } ?: request.fingerprint
        return when (val reservation = keys.reserve(key, fingerprint)) {
            is Reservation.Reserved -> {
                val answer = try {
                    made(tenant, member, request, guests)
                } catch (failed: SQLException) {
                    keys.release(key, reservation.token)
                    throw failed
                }
                // Nothing was made: the key is free for the same request once it can be.
                if (answer.status != CREATED) {
                    keys.release(key, reservation.token)
                    return answer
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

    /**
     * The booking, its slots and its confirmation in one transaction, as [tenant]: all commit, or none does. A clash or
     * a court the venue has not got rolls it back and is answered; so is [member] already holding their limit, unless
     * this is an event for [guests].
     */
    private fun made(tenant: String, member: String, request: BookingRequest, guests: Int?): Answer = try {
        tenants.transaction(TenantId(tenant)) {
            if (guests == null) {
                it.overLimit(member, request.slots)?.let { why -> return@transaction Answer(UNPROCESSABLE, why) }
            }
            val id = Booking.insert(it, request, member, guests)
            Jobs.enqueue(it, SendConfirmation, Confirmation(id), dedupeKey = "confirm-$id", tenant = tenant)
            Answer(CREATED, "booking $id")
        }
    } catch (refused: SQLException) {
        // A batch's failure carries its cause's state on the exception it chains to.
        when (refused.sqlState ?: refused.nextException?.sqlState) {
            EXCLUSION_VIOLATION -> Answer(CONFLICT, "a slot is already taken")
            FOREIGN_KEY_VIOLATION -> Answer(UNPROCESSABLE, "no such court")
            else -> throw refused
        }
    }

    private fun visible(tenant: String, bookingId: Long): Boolean =
        tenants.transaction(TenantId(tenant)) { Booking.find(it, bookingId) } != null

    // Built from the tenant and a number, so a key always parses.
    private fun ticket(tenant: String, bookingId: Long) =
        TenantPrefix(tenant).key("tickets/$bookingId.pdf").fold({ error("$it") }, { it })

    private fun notFound(bookingId: Long) = Answer(NOT_FOUND, "no booking $bookingId")

    private fun signedIn(token: Secret, permission: String, block: Principal.(tenant: String) -> Answer): Answer {
        val principal: Principal = sessions.resolve(token) ?: return Answer(UNAUTHORIZED, "sign in")
        val tenant = principal.tenant
        return when {
            tenant == null || !principal.can(permission) -> Answer(FORBIDDEN, "not allowed to $permission")
            else -> principal.block(tenant)
        }
    }

    companion object {
        const val CREATE = "bookings:create"
        const val VIEW = "bookings:view"
        const val MANAGE = "bookings:manage"
        const val HOST = "bookings:host"

        // Postgres's SQLSTATEs for the two refusals a booking expects.
        private const val EXCLUSION_VIOLATION = "23P01"
        private const val FOREIGN_KEY_VIOLATION = "23503"

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

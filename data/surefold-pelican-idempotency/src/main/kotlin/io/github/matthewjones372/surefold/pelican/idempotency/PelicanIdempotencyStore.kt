package io.github.matthewjones372.surefold.pelican.idempotency

import io.github.matthewjones372.pelican.IdempotencyScope
import io.github.matthewjones372.pelican.IdempotencyStore
import io.github.matthewjones372.surefold.idempotency.IdempotencyKey
import io.github.matthewjones372.surefold.idempotency.PostgresIdempotency
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration
import io.github.matthewjones372.pelican.Reservation as PelicanReservation
import io.github.matthewjones372.pelican.StoredResponse as PelicanResponse
import io.github.matthewjones372.surefold.idempotency.Reservation as SurefoldReservation
import io.github.matthewjones372.surefold.idempotency.StoredResponse as SurefoldResponse

/**
 * Pelican's [IdempotencyStore] on [store] (spec 0230), so a keyed endpoint's keys are shared by every instance.
 *
 * Pelican completes or releases a key on the node that reserved it, so the token each reservation answers is kept
 * here, by scope, until then. A node that dies holding one leaves the key to its lease, and a holder whose lease was
 * taken over writes nothing over the new holder's answer.
 */
class PelicanIdempotencyStore(private val store: PostgresIdempotency) : IdempotencyStore {

    private val held = ConcurrentHashMap<IdempotencyScope, UUID>()

    override fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): PelicanReservation {
        // Held until the lease, as a reservation is; complete keeps the answer for its ttl.
        val time = lease.toKotlinDuration()
        return when (val found = store.reserve(scope.key(), fingerprint, ttl = time, lease = time)) {
            is SurefoldReservation.Reserved -> PelicanReservation.Reserved.also { held[scope] = found.token }
            is SurefoldReservation.Replay -> PelicanReservation.Done(found.response.toPelican())
            SurefoldReservation.InProgress -> PelicanReservation.InFlight
            SurefoldReservation.Mismatch -> PelicanReservation.Mismatch
        }
    }

    override fun complete(scope: IdempotencyScope, response: PelicanResponse, ttl: Duration) {
        held.remove(scope)?.let { store.complete(scope.key(), it, response.toSurefold(), ttl.toKotlinDuration()) }
    }

    override fun lookup(scope: IdempotencyScope): PelicanReservation? = when (val found = store.lookup(scope.key())) {
        is SurefoldReservation.Replay -> PelicanReservation.Done(found.response.toPelican())
        SurefoldReservation.InProgress -> PelicanReservation.InFlight
        else -> null
    }

    override fun release(scope: IdempotencyScope) {
        held.remove(scope)?.let { store.release(scope.key(), it) }
    }

    private companion object {
        /** Between operation and subject: a unit separator, which neither an operation name nor a subject holds. */
        const val SEPARATOR = '\u001f'

        /** Holds a response's media type among its headers; a real header's name never starts with a colon. */
        const val MEDIA_TYPE = ":media-type"

        const val MOST_KEY = 255

        /** A key surefold-idempotency cannot hold as it is, blank or too long, goes as its SHA-256. */
        fun IdempotencyScope.key(): IdempotencyKey = IdempotencyKey(
            "$operation$SEPARATOR${subject.orEmpty()}",
            if (key.isNotBlank() && key.length <= MOST_KEY) key else "sha-256:" + sha256(key),
        )

        fun sha256(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

        fun PelicanResponse.toSurefold() =
            SurefoldResponse(status, listOfNotNull(mediaType?.let { MEDIA_TYPE to it }) + headers, body)

        fun SurefoldResponse.toPelican() = PelicanResponse(
            status,
            headers.filterNot { it.first == MEDIA_TYPE },
            headers.firstOrNull { it.first == MEDIA_TYPE }?.second,
            body,
        )
    }
}

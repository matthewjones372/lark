package io.github.matthewjones372.surefold.bookings

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZoneOffset.UTC
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A court, or a stylist: what a venue lets people book, by a name unique within the venue (spec 0232). */
data class Court(val name: String)

/** [court] held from [startsAt] for [length]. */
data class Slot(val court: String, val startsAt: Instant, val length: Duration) {
    val endsAt: Instant get() = startsAt + length.toJavaDuration()
}

/** A booking of every one of [slots], made together or not at all. */
data class BookingRequest(val name: String, val slots: List<Slot>) {
    /** What the idempotency key's first use is held to: a retry must ask for the same booking. */
    val fingerprint: String
        get() = "$name|" + slots.joinToString(",") { "${it.court}@${it.startsAt}+${it.length}" }
}

/** A booking, read through its table's policy: a tenant's transaction sees its own and no other's. */
data class Booking(val id: Long, val name: String, val startsAt: Instant) {
    companion object {
        /**
         * Inserts [request] and each of its slots as the transaction's tenant, which the tables' defaults fill in, as
         * [member]'s. A slot that overlaps one already held on its court throws the exclusion constraint's
         * [java.sql.SQLException].
         */
        fun insert(connection: Connection, request: BookingRequest, member: String): Long {
            val id = connection
                .prepareStatement("insert into bookings (name, starts_at, member) values (?, ?, ?) returning id")
                .use {
                    val first = request.slots.minOf { slot -> slot.startsAt }.atOffset(ZoneOffset.UTC)
                    listOf(request.name, first, member).forEachIndexed { at, value -> it.setObject(at + 1, value) }
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            connection.prepareStatement(
                "insert into booking_slots (booking_id, court, during) values (?, ?, tstzrange(?, ?, '[)'))",
            ).use { insert ->
                request.slots.forEach { slot ->
                    val row = listOf(id, slot.court, slot.startsAt.atOffset(UTC), slot.endsAt.atOffset(UTC))
                    row.forEachIndexed { at, value -> insert.setObject(at + 1, value) }
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            return id
        }

        fun find(connection: Connection, id: Long): Booking? =
            connection.prepareStatement("select id, name, starts_at from bookings where id = ?").use {
                it.setLong(1, id)
                it.executeQuery().use { rows -> if (rows.next()) rows.booking() else null }
            }

        fun all(connection: Connection): List<Booking> =
            connection.prepareStatement("select id, name, starts_at from bookings order by id").use {
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.booking() else null }.toList()
                }
            }

        private fun ResultSet.booking() =
            Booking(getLong("id"), getString("name"), getObject("starts_at", OffsetDateTime::class.java).toInstant())
    }
}

/** Adds [court] for the transaction's tenant; true if it was new. */
internal fun Connection.addCourt(court: Court): Boolean =
    prepareStatement("insert into courts (name) values (?) on conflict do nothing").use {
        it.setString(1, court.name)
        it.executeUpdate() == 1
    }

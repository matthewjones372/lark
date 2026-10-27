package io.github.matthewjones372.surefold.bookings

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class BookingRequest(val name: String, val startsAt: Instant)

/** A booking, read through its table's policy: a tenant's transaction sees its own and no other's. */
data class Booking(val id: Long, val name: String, val startsAt: Instant) {
    companion object {
        /** Inserts [request] as the transaction's tenant, which the table's default fills in. */
        fun insert(connection: Connection, request: BookingRequest): Long =
            connection.prepareStatement("insert into bookings (name, starts_at) values (?, ?) returning id").use {
                it.setString(1, request.name)
                it.setObject(2, request.startsAt.atOffset(ZoneOffset.UTC))
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
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

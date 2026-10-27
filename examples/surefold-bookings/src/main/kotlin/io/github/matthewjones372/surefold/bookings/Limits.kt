package io.github.matthewjones372.surefold.bookings

import java.sql.Connection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import java.time.temporal.TemporalAdjusters

/**
 * How many slots one member may hold in a day and in an ISO week, Monday to Sunday (spec 0232). A slot counts in the
 * day and the week its start falls in, in [zone]: the limit is about who plays when, not when they booked.
 */
data class Limits(val perDay: Int, val perWeek: Int, val zone: ZoneId) {
    init {
        require(perDay > 0 && perWeek > 0) { "a limit allows at least one slot, and this one is $perDay a day" }
    }
}

/** Sets the transaction's tenant's [limits], replacing any it had. */
internal fun Connection.setLimits(limits: Limits) {
    prepareStatement(
        """
        insert into booking_limits (per_day, per_week, zone) values (?, ?, ?)
        on conflict (tenant_id) do update set per_day = excluded.per_day, per_week = excluded.per_week,
            zone = excluded.zone
        """.trimIndent(),
    ).use {
        val row = listOf(limits.perDay, limits.perWeek, limits.zone.id)
        row.forEachIndexed { at, value -> it.setObject(at + 1, value) }
        it.executeUpdate()
    }
}

/**
 * Why [member] may not also hold [slots], or null if they may. It first locks the member's own row, so the count is
 * read with no other booking of theirs half done: one sent at the same time waits here, and then counts this one.
 */
internal fun Connection.overLimit(member: String, slots: List<Slot>): String? {
    val limits = limits() ?: return null
    lockMember(member)
    val days = slots.groupBy { it.startsAt.atZone(limits.zone).toLocalDate() }
    days.forEach { (day, here) ->
        val held = held(member, day.start(limits.zone), day.plusDays(1).start(limits.zone))
        if (held + here.size > limits.perDay) return "${limits.perDay} slots a day, and $held are booked on $day"
    }
    val weeks = slots.groupBy { it.startsAt.atZone(limits.zone).toLocalDate().monday() }
    weeks.forEach { (monday, here) ->
        val held = held(member, monday.start(limits.zone), monday.plusWeeks(1).start(limits.zone))
        if (held + here.size > limits.perWeek) {
            return "${limits.perWeek} slots a week, and $held are booked in the week of $monday"
        }
    }
    return null
}

private fun Connection.limits(): Limits? =
    prepareStatement("select per_day, per_week, zone from booking_limits").use {
        it.executeQuery().use { rows ->
            if (rows.next()) {
                Limits(rows.getInt("per_day"), rows.getInt("per_week"), ZoneId.of(rows.getString("zone")))
            } else {
                null
            }
        }
    }

private fun Connection.lockMember(member: String) {
    prepareStatement("insert into member_quota (member) values (?) on conflict do nothing").use {
        it.setString(1, member)
        it.executeUpdate()
    }
    prepareStatement("select member from member_quota where member = ? for update").use {
        it.setString(1, member)
        it.executeQuery().close()
    }
}

/** How many slots [member] holds that start in [from, until). */
private fun Connection.held(member: String, from: Instant, until: Instant): Int = prepareStatement(
    """
    select count(*) from booking_slots s join bookings b on b.id = s.booking_id
    where b.member = ? and lower(s.during) >= ? and lower(s.during) < ?
    """.trimIndent(),
).use {
    listOf(member, from.atOffset(UTC), until.atOffset(UTC)).forEachIndexed { at, value -> it.setObject(at + 1, value) }
    it.executeQuery().use { rows ->
        rows.next()
        rows.getInt(1)
    }
}

private fun LocalDate.start(zone: ZoneId): Instant = atStartOfDay(zone).toInstant()

private fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

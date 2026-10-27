package io.github.matthewjones372.surefold.bookings

/**
 * The changelogs the service's own master changelog includes, in this order: each module's tables, then the service's
 * own, whose policy reads the setting surefold-tenancy's transactions set.
 */
object Schema {
    val changelogs = listOf(
        "surefold/tenancy/postgres.sql",
        "surefold/jobs/postgres.sql",
        "surefold/idempotency/postgres.sql",
        "surefold/auth/postgres.sql",
        "bookings/postgres.sql",
    )
}

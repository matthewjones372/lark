package io.github.matthewjones372.surefold.bookings

import io.github.matthewjones372.surefold.fail.fold
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Confirmation(val bookingId: Long)

object ConfirmationCodec : JobCodec<Confirmation> {
    override val version = 1

    override fun encode(payload: Confirmation) = """{"bookingId": ${payload.bookingId}}"""

    override fun decode(version: Int, json: String) =
        Confirmation(json.substringAfter("\"bookingId\": ").substringBefore('}').trim().toLong())
}

/** Tells the customer their booking is made: enqueued in the transaction that makes it. */
object SendConfirmation :
    JobKind<Confirmation>("send-confirmation", ConfirmationCodec, deadline = 30.seconds, concurrency = 4)

fun interface Mailer {
    /** Whether the confirmation of [booking], in [tenant], was sent. Sending twice must be harmless. */
    fun confirm(tenant: String, booking: Booking): Boolean
}

/**
 * The worker's handlers. Give them to a `JobWorker` on the system `DataSource`, which claims across tenants; each
 * handler runs as its job's tenant, so it reads the booking through the same policy as a request.
 */
fun confirmations(tenants: TenantTransactions, mailer: Mailer): JobHandlers = JobHandlers.of(
    SendConfirmation.handledBy { job ->
        tenants.transaction { connection -> Booking.find(connection, job.payload.bookingId) }.fold(
            { JobResult.Fail("a confirmation needs its tenant") },
            { booking ->
                when {
                    booking == null -> JobResult.Skip("booking ${job.payload.bookingId} is gone")

                    // A transaction found its tenant, so the job has one.
                    mailer.confirm("${job.tenant}", booking) -> JobResult.Done

                    else -> JobResult.Retry(1.minutes, "the mailer is busy")
                }
            },
        )
    },
)

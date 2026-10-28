# 0229 — A booking service from the data modules

> Removed by spec 0248: booking is product code, not Surefold's; the example is no longer in the tree.

## Problem

Each data module has its own guide and its own tests, and none shows them together. A team building the multi-tenant
booking service the modules were made for has to work out for itself how a session's tenant reaches row-level
security, which `DataSource` the jobs worker takes, where an idempotency key is scoped, and in what order the
changelogs go. The first wrong answer leaks one tenant's bookings to another.

## Not doing

- No HTTP: the routes, cookies and status codes are Pelican's. The example is the layer a Pelican handler would call,
  and it answers what that handler would send.
- No Petshop changes: Petshop lives outside this repository.
- No real S3 or email: `InMemoryFiles` and a recording mailer stand in, as a service's own tests would use them.
- Not published: an example, like `surefold-bank`.

## Shape

```kotlin
val bookings = BookingService(
    tenants = TenantTransactions(app = appDataSource, system = systemDataSource),
    sessions = PostgresSessions(appDataSource, roles = PostgresRoles(appDataSource)),
    keys = PostgresIdempotency(appDataSource),
    files = filesFromConfig,
)
val worker = JobWorker(systemDataSource, confirmations(tenants, mailer)).start()   // crosses tenants, as it must

bookings.book(token, idempotencyKey = "k-1", BookingRequest("Table for two", at))   // Answer(201, "booking 7")
bookings.attachTicket(token, bookingId = 7, pdf, size)                             // under the tenant's prefix
bookings.ticketLink(token, bookingId = 7)                                          // presigned for ten minutes
bookings.list(token)                                                               // this tenant's, and no other's
```

- **One module**, `examples/surefold-bookings`, on `surefold-auth`, `surefold-tenancy`, `surefold-jobs`,
  `surefold-jobs-tenancy`, `surefold-idempotency` and `surefold-files`. No driver or pool in main.
- **Sessions** give the principal; its tenant is the only tenant the service ever binds, and its permissions gate
  each operation (`bookings:create`, `bookings:view`).
- **A booking** is inserted under `TenantTransactions` into a table with a forced policy, and the confirmation job is
  enqueued on the same connection, so both commit or neither does. The idempotency key is scoped to the tenant.
- **The worker** runs on the system `DataSource`; `surefold-jobs-tenancy` runs each handler as its job's tenant, so the
  handler reads the booking through the policy like any request.
- **Tickets** are stored under `TenantPrefix`, so a key from one tenant cannot name another's file.
- **Schema**: the order the changelogs are included in, as a list the example's tests apply, and the grants each role
  needs, in the guide.

## Why this shape

A service object a handler calls, rather than a web app, keeps the example to the modules it is about and needs no
Pelican release. **Alternative:** a runnable `main` with an embedded HTTP server, as `surefold-bank` has; recommended
against, since it would build the HTTP layer Pelican owns and add a server to the example's classpath.

## Stack

- [x] **`spec-0229-bookings`** — the module, `BookingService`, the confirmation job, and a suite that runs the whole
      flow on Postgres with row-level security.
      Done when: a booking retried with its key is made once and confirmed once by the worker as its tenant; a
      second tenant's session sees none of it, cannot attach to it, and cannot link its ticket; a session without the
      permission is refused.

## Acceptance

```bash
./gradlew :surefold-bookings:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Update Petshop, or a new example?** Recommended: a new example here, since Petshop is in another repository and
   would need these modules published first.
2. **A runnable `main`?** Recommended: no, as above; the suite is the demonstration.

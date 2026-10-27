# 0232 — Courts, parties and a weekly limit

## Problem

The booking example (spec 0229) books a name at a time, and nothing more. A tennis club, which is what a team
reaching for it usually is, needs three things it cannot say:

- **Several slots at once.** A member books court 2 from 18:00 and 19:00, or courts 1 and 2 together for doubles
  practice, and wants all of them or none. Today each would be its own booking, and nothing stops two members holding
  the same court at the same time: the table has no court and no end, so there is nothing to clash.
- **Events and parties.** The club books four courts on a Saturday afternoon for a junior tournament or a birthday
  party: a title, a guest count, several courts, and only staff may do it.
- **A fair share.** Members may book at most 2 slots a day and 5 a week, so the evening courts are not taken by the
  same few people. Two bookings sent at once by one member must not both get under the cap.

A hairdresser, the second tenant this spec uses, needs the same three: a stylist is a court, a colour-and-cut is two
slots back to back, a wedding party books three stylists for a morning, and a client may hold one appointment a day.

## Not doing

- **Cancelling, moving or waiting lists.** A booking is made or refused; a later spec can free its slots.
- **Prices, payment or opening hours.** Any slot on a court that exists can be booked.
- **Recurring bookings** ("every Tuesday at 7"). The caller sends each one.
- **HTTP.** As in 0229, the service answers what Pelican would send.
- **A new library module.** Everything lands in `examples/surefold-bookings`; nothing in `surefold-*` changes.

## Shape

```kotlin
// A venue's own setup, as its tenant: the courts it has, and how much one member may hold.
bookings.addCourt(staff, Court("court-1")); bookings.addCourt(staff, Court("court-2"))
bookings.setLimits(staff, Limits(perDay = 2, perWeek = 5, zone = ZoneId.of("Europe/London")))

// Several slots, all made or none. A clash with a slot already held is 409 and makes nothing.
bookings.book(member, "k-1", BookingRequest(
    name = "Evening hit",
    slots = listOf(Slot("court-2", at(18, 0), 60.minutes), Slot("court-2", at(19, 0), 60.minutes)),
))                                                     // Answer(201, "booking 7")

// An event: staff only (`bookings:host`), a title and a guest count; it does not count toward any member's limit.
bookings.host(staff, "k-2", EventRequest(
    title = "Junior tournament", guests = 24,
    slots = (1..4).map { Slot("court-$it", saturday(13, 0), 4.hours) },
))

// Over the limit: refused, and nothing is held.
bookings.book(member, "k-3", threeMoreSlots)           // Answer(422, "2 of 2 slots today are booked")
```

- **Schema:** `courts (tenant_id, name)`, and `booking_slots (booking_id, tenant_id, court, during tstzrange)` with
  an exclusion constraint on `(tenant_id, court, during &&)`. Postgres refuses the clash, and the transaction makes
  nothing. `bookings` gains `member` (the session's user), `kind` (`booking` or `event`) and `guests`. Every table has
  the forced tenant policy 0229's has.
- **Limits:** one row per tenant. A booking locks a per-member row (`member_quota`, upserted) before counting that
  member's slots in the day and week of each slot, in the tenant's `zone`. So two bookings from one member queue
  behind each other, and the second counts the first.
- **Permissions:** `bookings:create` books; `bookings:host` hosts events; `bookings:manage` adds courts and sets
  limits. The idempotency key and its fingerprint cover the whole request, slots included.
- **The suite** runs a tennis club (`acme-tennis`) and a hairdresser (`acme-hair`, stylists as courts, one
  appointment a day) side by side, so the same code is shown under two venues' rules, and neither sees the other's.

## Why this shape

Clashes are left to an exclusion constraint rather than a "select, then insert" in Kotlin. Read committed lets two
transactions both see a court free, and only the database can refuse the second. The limit cannot be a constraint,
since it spans rows over a window. The per-member row lock is the smallest serialisation that makes it hold: other
members never wait on it. **Alternative:** `serializable` for every booking transaction, with a retry. That needs no
lock to explain, but it retries unrelated bookings that touched the same index pages. Recommended: the lock.

## Stack

- [ ] **`spec-0232-slots`** — courts, `Slot`, a booking of several slots in one transaction, and the clash refused.
      Done when: two back-to-back slots on one court are one booking; a booking with a slot that overlaps another
      booking's is 409 and leaves none of its slots; the same court and time in another tenant does not clash.
      `booking_slots` holds each slot as a `tstzrange` under the forced tenant policy, with an exclusion constraint per
      tenant and court; a booking's slots go in one batch in its transaction. A clash (`23P01`) is 409 and an unknown
      court (`23503`) 422, and either releases the idempotency key, since nothing was made. `addCourt` needs
      `bookings:manage`. `bookings.starts_at` stays, as the first slot's start, for `list`.
- [ ] **`spec-0232-limits`** — `Limits` per tenant, counted per member per day and week in the tenant's zone.
      Done when: a member at 2 today is refused a third and booked tomorrow; at 5 this week is refused on any day of
      it; two concurrent bookings that together pass the cap are not both made.
- [ ] **`spec-0232-events`** — `host`, `EventRequest`, `bookings:host`, and events left out of member limits.
      Done when: a member without `bookings:host` is refused; an event's courts are held against members' bookings;
      an event leaves every member's count where it was.
- [ ] **`spec-0232-hairdresser`** — the second venue in the suite, and the example's guide section on both.
      Done when: the suite runs both venues' flows, each under its own limits, neither seeing the other's courts.

## Acceptance

```bash
./gradlew :surefold-bookings:build
```

## Open questions

Answered (maintainer, 2026-10-27: go with the recommendations).

1. **What does a limit count: slots or bookings?** Two back-to-back hours could be one or two against "2 a day".
   Recommended: slots, since the scarce thing is court time.
2. **Which day and week does a slot count in?** Recommended: the day and ISO week (Monday start) of the slot's
   start, in the tenant's `zone`. Not the day it was booked: the limit is about who plays when.
3. **Do events count toward anyone's limit?** Recommended: no. They are staff bookings for the club, and a member
   entered in one has not spent their share.
4. **Status for over the limit: 409, 422 or 429?** Recommended: 422. The request is well formed and the court is
   free, but the club's rule refuses it. 429 reads as "slow down", and 409 is kept for a clash.
5. **`btree_gist` for the exclusion constraint.** It is a contrib extension. Recommended: create it in the example's
   changelog (`create extension if not exists btree_gist`) and say in the guide that a managed Postgres must allow
   it. The alternative, a `serializable` check, is in "Why this shape".
6. **Slot lengths.** Free durations, or a grid (on the hour, 60 minutes)? Recommended: free, with the venue's
   grid left to the caller. A hairdresser's 45-minute appointment and a court's hour are then the same code.

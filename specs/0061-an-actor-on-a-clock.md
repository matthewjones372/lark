# 0061 — An actor on a clock

## Problem

A `lark-actor` actor cannot wait for anything but its next message. An adoption
that lapses after a week, a reminder two days before, a connection that gives
up if nothing is heard for thirty seconds: each needs a thread of its own that
sleeps and then tells the actor, written by hand, cancelled by hand, and racing
the actor when a stale one fires after the state it was meant for has gone. An
actor in a state that cannot take a message yet, a `Connecting` that is sent a
`Query`, has nowhere to keep it.

0059 put timers, receive timeout and stash off to here.

## Not doing

- **Cron-style schedules.** A delay and an interval; a `Schedule` can drive a
  periodic timer later if asked.
- **Timers that outlive the actor or survive a restart.** A restart or a stop
  cancels them all, and drops the stash.
- **Persisted timers.** That is 0063's journal, if ever.

## Shape

```kotlin
val adoption = behaviour<Adoption, Stage>(Open) { ctx, stage, message ->
    when (stage) {
        Open -> when (message) {
            is Approve -> ctx.become(Approved(message.applicant)) {        // timers that belong to this state
                after(5.days, Remind)
                after(7.days, Lapse)
            }
            is Query -> { ctx.stash(message); stay() }              // answered once approved
            else -> unhandled()
        }
        is Approved -> when (message) {
            Remind -> { notices.tell(CollectSoon(stage.applicant)); stay() }
            Lapse -> become(Open)                                    // leaving Approved cancels both
            is Query -> { message.reply(stage); stay() }
            else -> unhandled()
        }
    }
}

// in a test: time moves only when the test moves it, and nothing waits
val adoption = adoption.test()
adoption.send(Approve(sam))
adoption.advance(5.days)            // Remind is handled before this returns
adoption.pendingTimers shouldBe 1   // Lapse, still two days off
```

- **Keyed timers.** `ctx.timers.after(key, delay, message)` and
  `ctx.timers.every(key, interval, message)`, `cancel(key)`. Starting a key
  that is running replaces it, and a message from a cancelled or replaced
  timer never arrives, even one already in the mailbox. `every` is a fixed
  delay, measured from when the last one was handled, so a slow actor is never
  sent a burst of catch-up messages.
- **Timers that belong to a state.** `ctx.become(state) { after(delay, message) }`
  starts timers that end when the actor next becomes a state of another class.
  A `copy` of the same state, with a new field, keeps them; becoming a state
  with timers again replaces the ones it had. It is on `ctx`, so the timers'
  messages are typed as the actor's own.
- **Receive timeout.** `ctx.receiveTimeout(duration, message)` tells the actor
  `message` when nothing else has arrived for `duration`; any message resets it,
  `null` turns it off. It is a message of the actor's own type, handled in the
  same `when`, not a `Signal`.
- **Stash.** `ctx.stash(message)` keeps a message; `ctx.unstashAll()` puts every
  kept message back ahead of the mailbox, in the order kept. The stash holds
  1,024 by default (`spawn(…, stash = n)`, which an actor's children share),
  and stashing into a full one fails the step, so
  supervision decides; dropping silently would lose a message nobody knows
  about.
- **Time is the flock's clock** on threads, one waiting thread per flock for
  all its actors' timers, started with the first. On a `TestClock` no thread
  waits: `adjust` delivers what falls due at each instant on the way, in time
  order, and returns once the flock has handled it.
  `.test()` keeps its own time, starting at the epoch: `advance(by)` delivers
  what falls due in time order and runs to idle, and `pendingTimers` counts
  what is still to come, so a test can say nothing is left.

## Why this shape

Timers are messages, not callbacks: a timer's message arrives through the
mailbox and is handled by the step, so it is in order with everything else and
never races the state. Keys, with a replaced or cancelled timer's message
filtered even from the mailbox, are what make a stale timeout impossible rather
than unlikely. State-scoped timers remove the most common FSM bug, the timeout
nobody cancelled; the keyed form stays for a timer that spans states.

## Stack

- [x] **`spec-0061-timers`** ([#124](https://github.com/matthewjones372/lark/pull/124)) — `after`, `cancel`, keys, the flock's timer
      thread, `advance` and `pendingTimers`. Done when: a cancelled timer's
      message never arrives, on both runtimes, under `TestClock` on threads.
- [x] **`spec-0061-periodic`** ([#125](https://github.com/matthewjones372/lark/pull/125)) — `every` and `receiveTimeout`. Done when: an
      idle actor hears its timeout once per silence, and a message resets it.
- [x] **`spec-0061-scoped`** ([#126](https://github.com/matthewjones372/lark/pull/126)) — `ctx.become(state) { … }`. Done when: leaving the
      state cancels its timers, and a copy of the same state keeps them.
- [ ] **`spec-0061-stash`** — `stash`, `unstashAll`, the bound. Done when:
      unstashed messages are handled before the mailbox, in the order kept.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Nothing. All four were answered as recommended and are in Shape: `every` is a
fixed delay, state-scoped timers follow the state's class, the stash holds
1,024 and a full one fails the step, and the receive timeout is a message.

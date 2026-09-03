# 0003 — A drop-in for arrow-fx-coroutines

## Problem

An Arrow codebase on coroutines writes `either { parZip(…) }` and imports
`arrow.fx.coroutines`. Lark asks that code to wrap itself in `flock { }` and
to take a `Flock<E>` receiver, and its README leads with a Pelican binder. So
it is a library with an idea in it rather than a drop-in: a service that wants
Arrow on virtual threads has to learn a scope and pull in Pelican's shape of
the world. The maintainer's direction (chat, 2026-09-03): lark is what you
reach for *when using Arrow*, and nothing in it should be tied to Pelican.

## Not doing

- **Nothing of Pelican in this repository.** `lark-pelican` leaves; if the
  binder is wanted it is `pelican-lark` in Pelican's repo, depending on lark —
  the specific depending on the general, never the reverse.
- **No coroutine interop.** Nothing `suspend`, no `Flow`, no dispatcher. A
  codebase adopting lark drops `suspend` on the way in.
- **No `Atomic`, `CountDownLatch`, `CyclicBarrier`.** The JDK has them.
- **No `withTimeout` beyond a `timeout` over `raceN`.** A cancellation is an
  interrupt, and that stays said out loud.

## Shape

```kotlin
// arrow-fx-coroutines, today
suspend fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}

// lark: drop the `suspend`, swap the import, nothing else moves
fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

- `Raise<E>.parZip` (arities 2 to 9, as arrow-fx has), `Raise<E>.parMap`,
  `Raise<E>.raceN` (2 and 3): the receiver is Arrow's own `Raise`, so a body
  already inside `either { }` needs no scope of its own. Each call is a scope
  of its own underneath — today's `Flight` — and branches get a nested
  `Raise<E>`; the current `Flock<E>.` overloads become these, and a `Flock`
  is a `Raise`, so nothing compiled against them breaks.
- Top-level `parZip`/`parMap`/`raceN` for code outside any `Raise`, branches
  plain `() -> A`: the arrow-fx signatures minus `suspend`.
- `Raise<NonEmptyList<E>>.parZipOrAccumulate` and `parMapOrAccumulate`: every
  branch runs, every raise is kept, in arrow-fx's shape.
- `resourceScope { }` with `install(acquire) { r, exit -> release }` and
  `Resource<A>`, released in reverse on the way out by return, raise or throw.
- `Schedule` with `retry` and `repeat`, sleeping on the calling virtual thread.
- `timeout(duration) { }`: `raceN` against a sleeper, the loser interrupted.
- `flock { async { } … await() }` stays as the one thing arrow-fx does not
  have, and `Flock<E>` stays public for it.

## Why this shape

Same names, same signatures, same semantics as the library being replaced:
that is what "drop-in" means, and it is why the receiver has to be `Raise<E>`
rather than a scope of lark's own. The alternative — keep `Flock` as the
front door and document the wrapper — is a smaller change and a worse
library, because every adopter pays the wrapper on every function. The
implementation under the surface does not change: `Flight` already owns the
forks of one combinator, and a `Raise<E>` is all it ever needed from its owner.

## Stack

- [x] **`spec-0003-unpelican`** (`main`, 4413054) — `lark-pelican` removed; README reframed
      around the swap above; `main`'s spec 0002 last entry struck.
      Done when: `./gradlew build` is green with one module, and the README
      names no Pelican.
- [x] **`spec-0003-raise-par`** (`main`, 11b9929) — `Raise<E>.parZip` (2–9), `parMap`, `raceN`,
      and the top-level forms; `Flock<E>.` overloads become these.
      Done when: the dashboard above compiles inside a plain `either { }`,
      and `FlockTest`/`ParTest`/`RaceTest` pass unchanged.
- [x] **`spec-0003-accumulate`** (`main`, a13c508) — `parZipOrAccumulate`, `parMapOrAccumulate`.
      Done when: two raising branches answer a `NonEmptyList` of both.
- [x] **`spec-0003-resource`** (`main`, 24b5ddc) — `resourceScope`, `install`, `Resource`.
      Done when: releases run in reverse on return, raise and throw.
- [x] **`spec-0003-schedule`** (`main`, 913a451) — `Schedule.retry`/`repeat`, `timeout`.
      Done when: a retried raise stops after the schedule's last step, and a
      `timeout` interrupts the loser.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **`lark-pelican`: delete here and re-home as `pelican-lark`, or keep as an
   optional module?** Recommended: delete here. Its two commits stay in
   history and Pelican already has `pelican-arrow` as the pattern.
2. **Arity 9 or stop at 4?** Recommended: 9. A drop-in that fails to compile
   on the fifth branch is not one.
3. **`Resource<A>` the type, or `resourceScope` only?** Recommended: both, as
   arrow-fx — a `Resource` value is what gets passed between modules.
4. **Should `Flock` keep its name once `Raise` is the front door?**
   Recommended: yes; it is the scope for `async`/`await` only, and renaming it
   buys nothing.

Decided while building (2026-09-03), where arrow-fx and the JDK disagree:
a `raise` leaving a `resourceScope` is `ExitCase.Completed`, since `Cancelled`
here names an interrupt; `Decision.delayed` carries its transform into the
continuation so `jittered` jitters every delay, which arrow's does not; and
the arrow names win over the draft's (`Raise<E>.retry` / `retryRaise`).

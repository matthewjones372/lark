# 0139 — Lark on JDK 21

> **Declined, 2026-10-22.** Lark stays on JDK 25 as toolchain and floor. Pinning
> inside third-party libraries is the risk this spec could only report, not remove,
> and JDK 25 removes it. No stack entry was built.

## Problem

Every published module compiles for JDK 25 (`jvmToolchain(25)`), so a service on
JDK 21, still the most common LTS, cannot load any of them. No API forces this:
apart from the unpublished `lark-structured`, main code uses nothing newer than 21.
The reason is pinning. Before JDK 24 (JEP 491), a virtual thread that blocks inside
`synchronized` holds its carrier, and enough of them stall every fork in the JVM.

Pinning has two sources. Lark's own code has one: `Transact.commit` in `lark-kafka`
is `@Synchronized` and blocks on the broker. Every other monitor in main code only
sets a field or calls `interrupt()`. The other source is libraries a service calls
from a fork (a JDBC driver, a client, a serializer). Lark cannot change those, and
on 21 a user today has no way to see them short of `-Djdk.tracePinnedThreads`.

## Not doing

- **Rewriting third-party code**, or shading a driver to remove its monitors.
- **Raising carrier parallelism by default.** `jdk.virtualThreadScheduler.*` is the
  service's setting; the docs name it.
- **`lark-structured`.** Stays on 27 and unpublished.
- **The Gradle and compiler plugins.** Already built for 21.
- **Dropping 25 from CI.** 21 is the floor, 25 stays the recommendation.

## Shape

The libraries build for 21, CI runs on 21 and 25, and no monitor in Lark's own code
is held across a blocking call. On 21 to 23, Lark reports pinning instead of
leaving it silent:

```kotlin
// On by default when the JDK is older than 24, and a no-op on 24 and later.
// Each pinned park longer than the threshold becomes a warn line with the stack that pinned,
// and a count in lark.virtual_thread.pinned tagged by the top non-JDK frame.
fun main(): Unit = exitProcess(runApp(App).code)

// Off, or tuned, from the outside:
// -Dlark.pinning=off | -Dlark.pinning.threshold=20ms
```

The report comes from JFR's `jdk.VirtualThreadPinned` event, read through a
`jdk.jfr.consumer.RecordingStream` that `lark` opens once and closes on JVM exit.
`jdk.jfr` is part of the JDK, so `lark` still depends on nothing but the JDK and
`arrow-core`.

A test can hold code to "pins nothing":

```kotlin
// In lark's test fixtures: every jdk.VirtualThreadPinned event recorded while the block ran.
pinnedWhile { transact.commit(records, offsets, group) }.shouldBeEmpty()
```

## Why this shape

Lark can fix the pinning it causes and make the rest visible, but it cannot remove
pinning inside other libraries. So the fix for Lark's own code is mechanical: a
`ReentrantLock` where a monitor is held across I/O. The JFR report is what turns
"it might stall on 21" into a named stack in the service's own log. Two
alternatives were considered:

- **Run Lark's forks on platform threads when on 21.** That throws away what
  virtual threads give.
- **Documentation only.** That leaves a pinned carrier silent until a stall.

The recommendation is the report, on by default before 24, because a warning
nobody asked for costs less than a stall nobody can see.

## Stack

- [ ] **`spec-0139-toolchain-21`**: published modules build for 21, the doc tests'
      `jvmTarget` becomes 21, and CI's matrix runs 21 and 25.
      Done when: `./gradlew build` is green on both JDKs.
- [ ] **`spec-0139-no-monitor-across-io`**: `Transact.commit` takes a `ReentrantLock`;
      `pinnedWhile` lands in `lark`'s test fixtures, and a test checks a commit pins nothing.
      Done when: that test fails on 21 with `@Synchronized` restored, and passes as shipped.
- [ ] **`spec-0139-pinning-reported`**: the JFR stream, the warn line and
      `lark.virtual_thread.pinned`, on by default before JDK 24.
      Done when: a test that blocks inside `synchronized` on a fork, on 21, sees one
      line and one count; the same test on 25 sees neither.
- [ ] **`spec-0139-docs`**: README, `docs/modules.md`, `docs/core.md` and `llms.txt` say
      "JDK 21 or newer, 25 recommended", and explain pinning, the report and the scheduler settings once.
      Done when: no page says 25 is required.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build   # on JDK 21, then on JDK 25
```

## Open questions

1. **Report on by default before 24, or opt-in?** I recommend on by default: the case
   it exists for is the service that never thought to look.
2. **Which module owns the report?** I recommend `lark` itself, since `jdk.jfr` is in
   the JDK. The alternative is a `lark-jfr` module, if a JFR stream in core is too much.
3. **Any test sources that need JDK 22+ APIs?** I haven't audited test code yet. I
   recommend that the first stack entry finds and fixes those, rather than splitting
   them into an entry of their own.
4. **Does a release on 21 need its own version bump?** I recommend no: it widens
   support and removes nothing, so 0.10.x to 0.11.0 as usual.

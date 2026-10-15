# Testing

Lark's test support sits with the thing it tests. This page collects it in one
place and links to the detail. Most of it shares one idea: time and context
are values bound for a block, so a test can replace the clock, the logger or
the metrics registry without touching the code under test, and nothing has to
sleep.

| Need | Use | Module |
|---|---|---|
| a backoff, timeout or timer that does not wait | `clock.locally(fixedClock()) { }` or a `TestClock` | `lark` |
| assertions on log lines or metrics | `capturingLogs { }`, `capturingMetrics { }` | `lark` |
| a service test written as steps | `story { Given / When / Then }` | `lark-test` |
| waiting for something to become true | `eventually(within) { }` | `lark-test` |
| part of an application, with fakes | `testApp(module.subgraph<Root>().overriding(…)) { }` | `lark-app` |
| configuration for one test | `overridingConfig("key = value")` | `lark-app-typesafe` |
| a stream on a clock the test moves | `TestStreams(clock)` and `emitted()` | `lark-stream-test` |
| actors stepped on the calling thread | `behaviour.test()`, `testActors { }` | `lark-actor` |

The Gradle plugin's compiler checks also warn about `Thread.sleep` and
`TimeUnit.sleep` in test code (`LARK_SLEEP_IN_TEST`); see
[Applications](app.md#what-the-compiler-plugin-warns-about).

## Time

`clock` is a [`LarkLocal`](core.md#what-a-fork-inherits), and every wait in
Lark goes through it: `Schedule` delays, `timeout`, probe retries, actor
timers and the time-based stream operators.

- **`fixedClock()`** never advances, and a sleep on it returns at once. A
  five-retry exponential backoff runs in microseconds, and every log line is
  stamped with the same instant. Because `timeout` also sleeps on the clock, a
  `timeout` under `fixedClock` gives up immediately.
- **`TestClock()`** moves only when the test calls `adjust(by)` or
  `setTime(to)`. Each `adjust` releases every sleeper due by the new time, in
  time order. `adjustWhenBlocked(by)` first waits until every sleeping fork is
  waiting on a time still in the future, because a virtual thread gives no
  signal that it has reached its `sleep`.

<!-- testing-clock -->
```kotlin
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.retry
import kotlin.time.Duration.Companion.seconds

val backoff = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(5)

var attempts = 0

fun flakyRow(): String = if (++attempts < 4) error("not yet") else "row"

fun withoutWaiting(): String = clock.locally(fixedClock()) { backoff.retry { flakyRow() } }   // no real waiting
```

## Logs and metrics

`capturingLogs` and `capturingMetrics` bind an in-memory logger or registry
for a block, so a claim about logging or measuring is an assertion about
values:

<!-- testing-capture -->
```kotlin
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.parMap

fun linesLogged(): List<LogLine> = capturingLogs { logs ->
    logAnnotated("correlation_id" to "abc-123") { logInfo("registered") }
    logs.all()
}

fun adoptionsCounted(): Double = capturingMetrics { measured ->
    counter("petshop.adoptions").increment()
    measured.counter("petshop.adoptions")
}

// Under fixedClock every line is stamped with the same instant, and nothing waits.
fun stampedTogether(): List<LogLine> = clock.locally(fixedClock()) {
    capturingLogs { logs ->
        parMap(listOf(1, 2, 3)) { logInfo("branch $it") }
        logs.all()
    }
}
```

Both are in the core `lark` module.

## Stories and waiting

`lark-test` is for tests of a whole service. `story { }` writes a test as
`Given`, `When`, `Then`, `And` and `But` steps that return values. When a step
fails, the message shows the story up to that step and what it threw, in
colour under IntelliJ or with `FORCE_COLOR` set (`NO_COLOR` turns it off, and
`-Dlark.test.colour=always|never` overrides both).

`eventually(within) { }` retries a block every 20 ms by default until it stops
throwing, and gives up with `GaveUp`, an `AssertionError`, once `within` has
passed on the inherited clock. A step can wait the same way with
`Then("…").eventually(within) { }`.

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:lark-test:0.10.1")
}
```

```kotlin
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AdoptionTest {

    // `PetShop` belongs to the service
    @Test
    fun `an adopted pet is listed as taken`() = story {
        val shop = Given("a shop with three pets") { PetShop(listOf("Nibbles", "Barnaby", "Mrs Peel")) }
        val taken = When("Ada adopts the first") { shop.adoptFirst("Ada") }
        Then("Ada has Nibbles") { taken shouldBe "Nibbles" }
        And("the listing says so").eventually(within = 5.seconds) { shop.status("Nibbles") shouldBe "taken" }
    }
}
```

`lark-test` depends only on `lark`. It brings no assertion library; the
example uses Kotest's matchers, but any assertion that throws works.

## Applications

`testApp(module) { root: Root -> … }` starts a graph, runs the block with its
root, and releases the graph however the block ends. `subgraph<Root>()` keeps
only the nodes the root is reached through, and `overriding` replaces a node,
refusing a key the graph does not already have so that a typo cannot leave the
real node running. See [Testing an application](app.md#testing) and the
[cookbook](cookbook.md#test-one-thing-not-everything).

## Streams

`lark-stream-test` provides `TestStreams`, a backend that runs every stage on
the calling thread in a fixed order, with `tick`, `groupedWithin`,
`restartOnDefect` and `mapPar` waiting only on the test's `TestClock`. `start`
returns once the run is over or waiting on a later time, and `emitted()` reads
what a collecting run has produced so far:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

See [Time a test owns](stream.md#time-a-test-owns).

## Actors

`behaviour.test()` runs one actor on the calling thread, and
`testActors { }` runs several that message each other. A `send` returns once
that message and everything it caused has been handled, restarts record their
delays instead of waiting, and `advance` moves a test clock and delivers due
timers. `testActors` can also reorder deliveries from a seed and inject lost,
duplicated, delayed and crashed messages. See
[Testing actors](actors.md#testing), whose examples the build runs.

## Lark's own documentation tests

The guides' examples are compiled against the library by tests that read the
Markdown files: `ReadmeExampleTest`, `CoreGuideTest`, `CookbookTest`,
`ActorsGuideTest` and `GuideExampleTest`. A fence marked with an HTML comment
such as `<!-- actors-actor -->` is extracted, compiled, and in some cases run,
so a stale example fails the build.

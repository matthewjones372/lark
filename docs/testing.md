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
| checks that name the test's line on failure | `shouldBe`, `shouldBeRight`, `withClue { }` | `lark-assert` |
| tests that run concurrently | `object … : Suite()` | `lark-junit` |
| tests that share a resource and run concurrently | `object … : SharedSuite<R>()` | `lark-junit` |
| a story as a suite's test, returning its expectations | `story { }` | `lark-junit` |
| one class | its constructor, with fakes as arguments | nothing |
| several nodes together, one faked | `testApp(module.subgraph<Root>().overriding(…)) { }` | `lark-app` |
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
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.test.story
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
example uses [`lark-assert`](#assertions)'s, but any assertion that throws works.
In a suite, a story returns its expectations instead: see
[Stories in a suite](#stories-in-a-suite).

## Assertions

`lark-assert` holds the checks Lark's own tests use. Each one throws
opentest4j's `AssertionFailedError` when it fails, so any JUnit runner reports
it, and returns the value it checked when it holds. The failure says what was
expected, what was found, and the file, line and source of the check, and its
stack starts at the test's own frame:

```
expected: Right(value=42)
but was:  Left(value=Bad(why=broken))
at FlockTest.kt:322
    bound shouldBeRight 42
```

| Checks | On |
|---|---|
| `shouldBe`, `shouldNotBe`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeInstanceOf<T>`, `shouldBeSameInstanceAs` | any value |
| `shouldBeRight`, `shouldBeLeft` | Lark's `Either`, naming the side it found |
| `shouldBeLessThan`, `shouldBeGreaterThanOrEqual`, `shouldBeInRange`, … | anything `Comparable` |
| `shouldContain`, `shouldStartWith`, `shouldEndWith`, `shouldMatch`, `shouldNotBeBlank` | a string, or a `Regex` match within it |
| `shouldContain`, `shouldContainAll`, `shouldContainExactly`, `shouldContainExactlyInAnyOrder`, `shouldContainOnly`, `shouldHaveSize`, `shouldBeEmpty` | a collection |
| `shouldContainKey`, `shouldContain(key to value)`, `shouldContainAll`, `shouldContainExactly` | a map |
| `shouldThrow<T> { }` | a block, returning what it threw |

`shouldBe` and `shouldContainExactly` carry both sides, so an IDE can show
them side by side. `withClue(clue) { }` puts the clue above any failure inside
its block; a clue written as a lambda is only worked out if something fails.
*Unreleased: `lark-assert` is on `main` and not in 0.10.1.*

## Suites

`lark-junit` runs tests on the JUnit Platform with Lark's own concurrency. A
suite is a Kotlin `object`, which the `lark` engine finds with no annotation. A
test is registered from `init` and returns [`Expectations`](#expectations).

- **`Suite`:** tests that share nothing, registered with `test(name) { … }`.
- **`SharedSuite<R>`:** tests that share one `R`, registered with
  `test(name) { shared -> … }`. Its `shared` resource is acquired once, handed
  to every test, and released after the last. If acquiring throws, each test
  fails with that throw, and what was installed before it is released.

```kotlin
object SortingSuite : Suite() {
    init {
        test("a list is sorted smallest first") { expect(listOf(3, 1, 2).sorted() == listOf(1, 2, 3)) }
    }
}
```

- **Concurrency:** each test is a fork on a virtual thread, at most the
  suite's `Limit` at once, `Limit.fixed(availableProcessors)` by default.
  Suites run one after another. A test given the [`alone`](#aspects) aspect
  waits for those registered before it, and later ones wait for it;
  `Suite(concurrent = false)` runs every test alone.
- **Logs:** each test logs and measures into a capture of its own. A passing
  test prints nothing. A failed test's lines are printed under its name and
  published to the platform as a `logs` report entry.
- **Reporting:** results go through the JUnit Platform, so Gradle's JUnit XML,
  IntelliJ's test tree and CI read a suite as they read a Jupiter class, and
  the two run side by side in one `test` task. A test's name is its display
  name and its ID, so two tests in a suite cannot share one.

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:lark-junit:0.10.1")
}

tasks.test { useJUnitPlatform() }
```

<!-- testing-junit-suite -->
```kotlin
import io.github.matthewjones372.lark.Limit
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.alone
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentHashMap

// Stands in for the service under test.
class PetShop {
    private val pets = ConcurrentHashMap<String, String>()

    fun list(name: String) {
        pets[name] = "available"
    }

    fun status(name: String): String? = pets[name]

    fun count(): Int = pets.size

    fun close() = pets.clear()
}

object PetShopSuite : SharedSuite<PetShop>(limit = Limit.fixed(8)) {
    override val shared: Resource<PetShop> = resource { install({ PetShop() }) { shop, _ -> shop.close() } }

    init {
        test("a listed pet is available") { shop ->
            logInfo("listing Nibbles")                      // printed only if this test fails
            shop.list("Nibbles")
            expect(shop.status("Nibbles") == "available")
        }
        test("an unlisted pet has no status") { shop -> expect(shop.status("Mrs Peel") == null) }
        test("one pet is listed", alone) { shop -> expect(shop.count() == 1) }   // after the tests above
    }
}
```

### More than one resource

A suite has one `shared` resource, but it can hold several things. Write each
as its own `Resource`, and build the suite's from them in one `resource { }`
block, where `.bind()` acquires each one. They are released in reverse order,
and if one fails to start, those already started are released. Something a
single test needs fresh is acquired in that test, with `use`, and released
when the test ends.

<!-- testing-junit-resources -->
```kotlin
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import io.github.matthewjones372.lark.use
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// Stand in for a database and a broker: each started once for the suite, closed after its last test.
class Orders {
    private val saved = ConcurrentHashMap<String, Int>()

    fun save(id: String, count: Int) {
        saved[id] = count
    }

    fun count(id: String): Int = saved[id] ?: 0

    fun close() = saved.clear()
}

class Broker {
    private val sent = CopyOnWriteArrayList<String>()

    fun publish(event: String) {
        sent += event
    }

    fun published(prefix: String): List<String> = sent.filter { it.startsWith(prefix) }

    fun close() = sent.clear()
}

// A recorder each test gets fresh: what one test saw, and nothing another test did.
class Recorder {
    val seen = CopyOnWriteArrayList<String>()

    fun close() = seen.clear()
}

// Each piece is its own Resource, so a suite that needs one alone can take just that one.
fun orders(): Resource<Orders> = resource { install({ Orders() }) { db, _ -> db.close() } }

fun broker(): Resource<Broker> = resource { install({ Broker() }) { broker, _ -> broker.close() } }

fun recorder(): Resource<Recorder> = resource { install({ Recorder() }) { recorder, _ -> recorder.close() } }

class Shop(val orders: Orders, val broker: Broker)

object ShopSuite : SharedSuite<Shop>() {
    // Acquired in order and released in reverse: the broker, then the orders. If the broker fails to start,
    // the orders already started are released, and every test fails with the broker's error.
    override val shared: Resource<Shop> = resource { Shop(orders().bind(), broker().bind()) }

    init {
        test("an order is saved and published once") { shop ->
            shop.orders.save("order-1", 2)
            shop.broker.publish("order-1:placed")
            (shop.orders.count("order-1") shouldBe 2) and
                (shop.broker.published("order-1") shouldBe listOf("order-1:placed"))
        }
        test("a test that needs its own resource acquires it, and it is released when the test ends") { shop ->
            recorder().use { recorder ->
                shop.broker.publish("order-2:placed")
                recorder.seen += "order-2:placed"
                recorder.seen.toList() shouldBe shop.broker.published("order-2")
            }
        }
    }
}
```

### State in a suite

A suite is one `object`, so its properties are shared by every test in it, and
by tests running at the same time. Jupiter makes a new instance of the class
for each test, so a field there starts fresh each time; in a suite it does
not. A value a test changes, such as a counter, a list it adds to or an
exception it attaches notes to, belongs inside the test, or in a function the
test calls to make a fresh one. Put what the tests share on purpose in
`shared`.

A test that needs a directory of its own, as Jupiter's `@TempDir` gives one,
calls `withTempDirectory { dir -> … }`. The directory is new and empty, and
it is deleted with everything in it once the block returns or throws.

## Expectations

A suite's test returns what its checks found as a value, `Expectations`, so a
failed check does not hide the ones after it, and a test that checks nothing
does not compile: a body that returns `Unit` is not `Expectations`.
`expect(condition)` is the one check every other is built from. `a and b`
keeps both sides' failures, `a or b` passes if either side does, and `!a`
passes if `a` failed. One failure is reported alone, and several as one
`MultipleFailuresError`.

Built with JetBrains' power-assert plugin, a failed `expect` draws each value
under the expression, then the test's file and line:

```
an order is saved once: 1 of 2 expectations failed
expect(db.count() == 1)
       |  |       |
       |  2       false
       Database(orders=2)
at OrderSuite.kt:32
```

Without the plugin, it quotes the source line instead. A diagram carries no
expected and actual for IntelliJ's diff, so `lark-junit` also has `shouldBe`,
`shouldNotBe`, `shouldBeRight` and `shouldBeLeft`, which return `Expectations`
and carry both. Beside them are `shouldContain` and `shouldNotContain`, for a
string or a collection, `shouldStartWith`, `shouldEndWith`, `shouldMatch`, a regex
`shouldContain` and `shouldNotBeBlank` for a string, `shouldContainExactly`,
`shouldContainExactlyInAnyOrder`, `shouldContainOnly`,
`shouldContainAll`, for a collection or a map, `shouldHaveSize`, `shouldBeEmpty`
and `shouldNotBeEmpty`, `shouldContainKey` and `shouldNotContainKey`,
`shouldBeNull`, the comparisons `shouldBeLessThan`, `shouldBeLessThanOrEqual`,
`shouldBeGreaterThan`, `shouldBeGreaterThanOrEqual` and `shouldBeInRange`, and
`shouldBeSameInstanceAs` and `shouldNotBeSameInstanceAs`. Four take a lambda
that runs only when they hold: `x.shouldNotBeNull { a -> … }` checks the
value, `x.shouldBeInstanceOf<T> { t -> … }` checks it as a `T`, and
`e.shouldBeRight { a -> … }` and `e.shouldBeLeft { e -> … }` check what an
`Either` holds. `xs.forAll { x -> … }` checks every element, keeping each one's
failures, and holds on no elements. `shouldThrow<T>(block)` holds when `block`
throws a `T`; `shouldThrow<T>(block) { e -> … }` then checks what it threw. A
different exception fails the test with that exception as its cause, and an
interrupt or cancellation goes on up, so a `timeout` still stops the test. Infix calls share one precedence and group to the left, so
`a shouldBe 1 and b shouldBe 2` means `((a shouldBe 1) and b) shouldBe 2`:
parenthesise each matcher. Import these from `lark-junit`, not `lark-assert`,
whose matchers throw and return the value; a file importing both can call
neither. A throwing check, such as `lark-assert`'s, is still allowed in a
test: it stops the test, which is reported as an error.

<!-- testing-junit-expect -->
```kotlin
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBeLeft
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource

data class NotFound(val id: String)

class Database {
    private val orders = mutableListOf<String>()

    fun save(order: String) {
        require(order.isNotBlank()) { "an order needs an id" }
        orders += order
    }

    fun count(): Int = orders.size

    fun find(id: String): Either<NotFound, String> =
        orders.firstOrNull { it == id }?.let { Either.Right(it) } ?: Either.Left(NotFound(id))

    override fun toString(): String = "Database(orders=${orders.size})"
}

object OrderSuite : SharedSuite<Database>(concurrent = false) {
    override val shared: Resource<Database> = resource { Database() }

    init {
        test("an order is saved once") { db ->
            repeat(2) { db.save("order-1") }
            expect(db.count() == 1) and (db.find("order-1") shouldBeRight "order-1")
        }
        test("an order never saved is not found") { db ->
            (db.find("order-9") shouldBeLeft NotFound("order-9")) and !expect(db.count() == 0)
        }
        test("an order without an id is refused") { db ->
            shouldThrow<IllegalArgumentException>({ db.save("") }) { refusal -> refusal.message shouldContain "an id" }
        }
    }
}
```

The build needs the plugin, with `expect` in its functions, on the test
source set. `Expectations` is marked for Kotlin's return-value checker, which
reports a dropped one only under `-Xreturn-value-checker=check`, and fails the
build only with `allWarningsAsErrors`. Without the flag, a test written
`expect(a); expect(b)` compiles, and the first expectation is lost.

```kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.power-assert") version "2.4.10"
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions.add("io.github.matthewjones372.lark.junit.expect")
    includedSourceSets.add("test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xreturn-value-checker=check")
        allWarningsAsErrors = true
    }
}
```

## Stories in a suite

`lark-junit` has a `story` of its own, for a suite's test: its block returns
`Expectations`, and so does the story. A step returns what its block does, so
`Then("…") { a shouldBe b }` is `Expectations`, and joins the next step with
`and`. A step whose expectations failed is marked `✗` and the story goes on,
so every failed `Then` is reported; a step that throws still ends the story
with a `StoryFailed`.

- **Title:** an untitled story takes its test's name.
- **Transcript:** it is one of the test's [log lines](#suites), so a passing
  test prints nothing, and a failed test's transcript is printed and published
  with the rest of its `logs`. Outside a suite, `lark-test`'s `story` prints to
  stdout as before.
- **Imports:** import `story` from `lark-junit`, which brings `lark-test` with
  it. `lark-test`'s `story` returns `Unit`; a file importing both can call
  neither until one is imported under another name.

The return-value checker does not catch a `Then` on a line of its own: the
steps are `lark-test`'s, generic over what their block returns, so their
results are not marked for it. Join every `Then` with `and`, or end the story
with it.

<!-- testing-junit-story -->
```kotlin
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentHashMap

// Stands in for the service under test.
class Orders {
    private val saved = ConcurrentHashMap.newKeySet<String>()

    fun clear() = saved.clear()

    fun save(order: String) {
        saved += order
    }

    fun count(): Int = saved.size

    fun find(order: String): String? = order.takeIf { it in saved }
}

object OrderStorySuite : SharedSuite<Orders>(concurrent = false) {
    override val shared: Resource<Orders> = resource { Orders() }

    init {
        test("an order is saved once") { orders ->
            story {                                                  // titled "an order is saved once"
                Given("no orders") { orders.clear() }
                When("the order is saved twice") { repeat(2) { orders.save("order-1") } }
                Then("it is stored once") { orders.count() shouldBe 1 } and
                    Then("it can be found") { orders.find("order-1") shouldBe "order-1" }
            }
        }
    }
}
```

## Aspects

An aspect says how a suite's test runs. It is given beside the test's name,
`test(name, flaky(3)) { }`, or to the suite for each of its tests,
`Suite(aspects = tag("io"))`. Aspects are known when the test is registered,
so discovery reads tags and `ignore` without running anything.

| Aspect | What it does | Reported as |
|---|---|---|
| `flaky(n)` | runs the test up to `n` times, until an attempt passes | passed if one does, with an `attempts` report entry such as `2 of 3`, given on failure too |
| `nonFlaky(n)` | runs the test `n` times, and every run must pass | failed at the first failing run: `run 7 of 50 failed: …` |
| `timeout(d)` | interrupts the test if it runs longer than `d`, on Lark's `timeout` | failed with a `TimeoutException`, `no answer within d` |
| `ignore(why)` | does not run the test | skipped, with `why` |
| `tag(name)` | a JUnit tag, which Gradle's `includeTags` and the IDE filter by | — |
| `alone` | nothing else in the suite runs beside the test | — |

`+` combines aspects, and the left one wraps the right one.
`timeout(5.seconds) + flaky(3)` gives all three attempts five seconds
together, and an attempt cut off by it is not retried;
`flaky(3) + timeout(5.seconds)` gives each attempt five seconds, and retries
one that runs out. A suite's aspects wrap each of its tests'. Tags add up, a
test is `alone` if either side is, and an `ignore` on the left gives its reason
before one on the right.

A test with no `timeout` of its own, nor one from its suite, is held to the
`lark.junit.timeout.default` configuration parameter or system property, if
set: a duration such as `60s`, as Jupiter's
`junit.jupiter.execution.timeout.default` is. This repository's build sets both
to 60 seconds.

<!-- testing-junit-aspects -->
```kotlin
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.alone
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.flaky
import io.github.matthewjones372.lark.junit.ignore
import io.github.matthewjones372.lark.junit.nonFlaky
import io.github.matthewjones372.lark.junit.tag
import io.github.matthewjones372.lark.junit.timeout
import io.github.matthewjones372.lark.resource
import kotlin.time.Duration.Companion.seconds

// Stands in for a connection to the service under test.
class Wire {
    fun roundTrip(frame: String): String = frame

    fun reconnects(): Boolean = true
}

// Every test is tagged "io", and each runs within 30 seconds, retries and all.
object WireSuite : SharedSuite<Wire>(aspects = tag("io") + timeout(30.seconds)) {
    override val shared: Resource<Wire> = resource { Wire() }

    init {
        test("frames survive a slow reader", nonFlaky(50) + alone) { wire ->
            expect(wire.roundTrip("frame") == "frame")
        }
        test("reconnects after a reset", flaky(3) + timeout(5.seconds)) { wire -> expect(wire.reconnects()) }
        test("speaks HTTP/3", ignore("no QUIC on CI")) { _ -> expect(false) }
    }
}
```

`tasks.test { useJUnitPlatform { includeTags("io") } }` then runs only the
tagged tests, Jupiter's `@Tag`s among them.

## Applications

`testApp(module) { root: Root -> … }` starts a graph, runs the block with its
root, and releases the graph however the block ends. `subgraph<Root>()` keeps
only the nodes the root is reached through, and `overriding` replaces a node,
refusing a key the graph does not already have so that a typo cannot leave the
real node running. See [Testing an application](app.md#testing) and the
[cookbook](cookbook.md#test-the-wiring-not-one-class). A test of one class
needs none of this: it calls the class's constructor.

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
Markdown files: `ReadmeExampleSuite`, `CoreGuideTest`, `CookbookTest`,
`ActorsGuideTest` and `GuideExampleTest`. A fence marked with an HTML comment
such as `<!-- actors-actor -->` is extracted, compiled, and in some cases run,
so a stale example fails the build.

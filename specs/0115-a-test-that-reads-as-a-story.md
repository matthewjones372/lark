# 0115 — A test that reads as a story

## Problem

A test of a whole lark service, like the petshop's `EndToEndSpec`, is a story:
a shop with pets, somebody adopts one, the outbox drains, `/stats` counts it.
Today that story lives in comments and `withClue` strings, and the last part has
to wait. Kotest's `eventually` is `suspend`, and the block of `use` and
`testApp` is not, so a caller wraps it in `runBlocking` or writes a loop. When a
test fails, the report names an assertion and its line, not where in the story
it broke. In CI, where nobody reads the test's stdout, nothing says which steps
had already passed.

A prototype runs in the petshop (`app/src/test/kotlin/petshop/app/Story.kt`,
matthewjones372/petshop#20). The shape below is what it does, and the open
questions are what it found.

## Not doing

- A test framework. Tests stay JUnit `@Test` methods (or Kotest specs); this is a
  library called inside one.
- `suspend`. Waiting blocks, on lark's `clock`.
- Colour. The console output here is plain text; [0116](0116-a-story-in-colour.md) colours it.
- Gherkin, feature files, or a step registry. A step is a lambda at the call site.
- Shared state between steps. A step returns a value, and the next step uses it.

## Shape

A new module, `lark-test`, depending on `lark` and `kotest-assertions-core`:

```kotlin
@Test
fun `somebody adopts a tortoise`() = story {
    theService.use { server: PelicanServer ->
        apiClient(server.baseUrl, JacksonCodecs).use { shop ->
            val pet = When("Ada adopts Nibbles") { shop.outcome(adoptPet, 1L) }
            Then("Nibbles is hers") { pet.shouldBeOk().adopted shouldBe true }
            And("the outbox drains").eventually(5.seconds) { database.unsent() shouldBe 0L }
        }
    }.shouldBeRight()
}
```

On the console, in plain text (colour is [0116](0116-a-story-in-colour.md)), as
the prototype printed it with the outbox check broken on purpose:

```
Story: somebody adopts a tortoise
  ✓ When Ada adopts Nibbles             153 ms
  ✓ Then Nibbles is hers                0 ms
  ✗ And the outbox drains               5.0 s, 130 tries
      expected:<7L> but was:<0L>
```

- `Given`/`When`/`Then`/`And`/`But` run their block, time it, and return its
  value. They are capitalised because `when` is a keyword.
- `story` returns `Unit`, so `fun test() = story { }` is a test JUnit runs; a
  test method returning a value is skipped. Its title defaults to the calling
  method's name, read off the stack, which is the backticked test name.
- **The transcript prints when the story ends**, not as each step runs, so a
  nested step's line sits under its parent and the timings line up.
- **A failure carries the transcript.** The thrown `StoryFailed`, an
  `AssertionError` with the original as its cause, has the story up to and
  including the failing step as its message, in plain text, so it shows in CI,
  in the JUnit XML and in IntelliJ, whatever happens to stdout. A failure is
  told once, under the innermost step that saw it; the steps around it are
  marked failed without repeating it.
- `eventually(timeout, every = 20.milliseconds)` is
  `Schedule.spaced(every) zipLeft Schedule.upTo(timeout)` from
  [0114](0114-a-schedule-that-gives-up-in-time.md), retried. It rethrows the
  last failure, and the step's line shows the tries and elapsed time. A
  `TestClock` drives it. Bounded by time, it gave up at 5.0 s in the prototype,
  where `recurs(250)` with a SQL query per try took 12.
- **Steps nest.** A step that runs steps of its own indents them, so a helper
  can tell its own part of the story.

## Why this shape

Values flowing out of steps keep the test as straight-line Kotlin: no context
object, no `lateinit`, and the compiler checks what each step hands the next.
The alternative is Kotest's `BehaviorSpec`, which already has Given/When/Then.
But it is a whole framework with its own runner and lifecycle, the blocks
cannot return values, and lark's tests and the petshop's run on JUnit.
Recommended: the library. A transcript in the failure message rather than only
on stdout is the part CI actually benefits from.

## Stack

Depends on 0114's `spec-0114-up-to`.

- [ ] **`spec-0115-eventually`** — the `lark-test` module, `eventually`, and its
      `NoOtherDependenciesTest`.
      Done when: on a `TestClock`, a failing `eventually(5.seconds)` gives up
      once the clock has moved five seconds, and its message names the last
      failure, the tries and the elapsed time.
- [ ] **`spec-0115-steps`** — `story`, the five steps, nesting, timings, the
      plain console lines, and the transcript in a failure's message.
      Done when: a story failing at its third step throws an `AssertionError`
      whose message lists steps one and two as passed and three as failed,
      with the original message, and a failure inside a nested step is told
      once. The prototype's `StorySpec` is the test to port.

## Acceptance

```bash
./gradlew :lark-test:test
./gradlew build
```

Then the petshop's `EndToEndSpec`, rewritten as a story, reads like the example
under Shape.

## Open questions

- **Kotest on a consumer's classpath.** `kotest-assertions-core` brings
  `kotlinx-coroutines` with it when it runs. Should it be `api` (simplest) or
  `compileOnly`, with the consumer bringing their own Kotest? Recommended:
  `api`. It is a test dependency, and "no coroutines" is about lark's own code.
- **Where the console output goes.** Gradle hides a test's stdout unless
  `showStandardStreams` is on. Also publish each step as a JUnit `ReportEntry`?
  Recommended: stdout only; the transcript in the failure covers CI.
- **lark's log lines during a step.** Indent them under the step they came from?
  Recommended: not in this spec. It needs the `Logger` local, and is its own change.
- **`story` as a wrapper, or steps usable bare?** Bare steps lose the header and
  the transcript. Recommended: `story { }` required.
- **A step that starts the service.** `Given("…") { started(theService) }`
  cannot work today: `Module.use` keeps the graph only inside its block, so the
  prototype's story sits inside `use` rather than starting the service as a
  step. Making it a step needs the story to own a `ResourceScope`, and
  `lark-app` to start a graph into one. Recommended: a follow-up spec in
  `lark-app`; this one works inside `use`.
- **Steps inside `eventually`.** Each try reruns the block, so a nested step is
  recorded once per try. Record only the last try's? Recommended: yes.

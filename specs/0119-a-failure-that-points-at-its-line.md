# 0119 — A failure that points at its line

## Problem

[0115](0115-a-test-that-reads-as-a-story.md)'s `story` ends a failed story
with `StoryFailed`, thrown from `story()` itself. Its stack trace starts in
`Story.kt`, so the first line an IDE links to is lark's, and the assertion that
failed is further down, under "Caused by":

```
  ✗ And the other nineteen are told she is taken    5 ms
      expected:<32> but was:<19>
	at io.github.matthewjones372.lark.test.StoryKt.story(Story.kt:25)
	at io.github.matthewjones372.lark.test.StoryKt.story$default(Story.kt:19)
	at petshop.app.AdoptionSpec.twenty people adopt one tortoise…(AdoptionSpec.kt:39)
```

The line it names in the test is where the story starts, not the step that
failed. The petshop's prototype has the fix, matthewjones372/petshop#28.

## Not doing

- Changing the transcript's wording or layout beyond one line.
- Changing the cause. It is the step's own throw, untouched.
- Any IDE plugin. Plain stack frames are what IDEs already link.

## Shape

```
  ✗ And the other nineteen are told she is taken    5 ms
      expected:<32> but was:<19>
      at petshop.app.AdoptionSpec.…(AdoptionSpec.kt:49)
	at petshop.app.AdoptionSpec.…(AdoptionSpec.kt:49)
	at io.github.matthewjones372.lark.test.Story.step(Story.kt:69)
	…
```

- **Where it failed** is the first frame of the step's throw that is not
  the story's or `eventually`'s, lark's core, an assertion library's (Kotest,
  opentest4j), Kotlin's, Arrow's or the JDK's. For a
  `GaveUp`, it is the last failure's.
- **The transcript prints that frame** under the failing step's message, as
  `at <frame>`, which an IDE's console turns into a link. It goes in the plain
  message and the console copy alike; in colour it is dim.
- **`StoryFailed`'s stack trace starts at that frame**, and keeps the frames
  after it. The first line a test runner links to is the assertion.
- When no frame qualifies, nothing is printed, and the stack trace is left as
  it is.

## Why this shape

The frame is already in the throw. Printing it and leading the stack trace with
it costs nothing at run time and needs nothing from the IDE. The alternative is
rethrowing the step's own throw rather than wrapping it, which keeps its stack
but loses the transcript as the message. Recommended: wrap, as now, and lead
with the frame.

## Stack

- [ ] **`spec-0119-points-at-its-line`** — the frame under the failing step,
      and `StoryFailed`'s stack trace led by it.
      Done when: a story failing on a `shouldBe` in a test throws a
      `StoryFailed` whose first stack frame is in that test's file at the
      `shouldBe`'s line, and whose transcript's last line is `at` that frame;
      a `GaveUp` points at its last failure's line.

## Acceptance

```bash
./gradlew :lark-test:test
```

## Open questions

- **Which frames are lark's?** Not the whole `io.github.matthewjones372.lark.`
  prefix: `lark-test`'s own tests are under it, and would point past
  themselves. Recommended: lark's core package itself (schedules, retry, the
  clock), and `lark-test`'s `Story` and `eventually` classes.
- **Kotest's own frames.** Kotest trims its frames by default; others may not.
  Recommended: skip `io.kotest.` and `org.opentest4j.` whatever the setting.

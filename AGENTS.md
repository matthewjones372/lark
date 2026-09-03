# Working in this repo

Lark is a leaf module beside [Pelican](https://github.com/matthewjones372/pelican),
and it works the way Pelican does. Pelican's `AGENTS.md` is the long form; the
rules below are the ones that decide whether a change here is accepted.

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built. A draft is
one page: fill the template's headings, put uncertainty under Open questions,
give options with a recommendation rather than a verdict, and stop.
`specs/README.md` gives the layout and the lifecycle.

Before a spec exists: ask questions, read code, answer in chat. No code.

## One spec section per pull request

Pull requests are stacked, each reviewable on its own, under ~200 changed
lines. One stack entry is one branch. Announce the split before the first
commit. Branch from `origin/main` and set `git config rebase.updateRefs true`
once so a rebase carries the branches above it.

## Layout

`lark` depends on `pelican-arrow` — which is `pelican-core` plus `arrow-core` —
and on nothing else. That claim is a test, `NoOtherDependenciesTest`, asserting
the main runtime classpath. No HTTP library, no JSON library, no coroutines.
Virtual threads come from the JDK.

## Values, errors and effects

A handler's declared failures are values in the endpoint's type. `raise` is the
only way to leave a handler with one; throwing is for what nobody declared.
`catch (t: Throwable)` belongs in the one place that completes the stage, and
nowhere else — a raise unwinds through the body as an exception, and a catch
between it and the boundary eats it.

A `Raise` scope never crosses a thread. Work forked from a handler answers with
an `Either` and the handler binds it.

Never add an `else` to a `when` over a sealed type.

## Comments and imports

A comment records a why, in one or two sentences, and nothing that the
signature already says. No history, no rhetoric. KDoc is one line by default.

No wildcard imports and no unused ones, in sources, tests and docs alike.
Every complete example in the README carries its imports and its
`dependencies { }` block.

## Testing

Write the failing test first. Test names are sentences in backticks; Kotest
matchers, JUnit 5, `withClue` where a bare boolean would not explain itself.
Behaviour is tested through Pelican's typed test client — `app.call(getUser,
1L)` — never through path strings or hand-written JSON.

## Verifying

`./gradlew build` is the gate: compile, detekt, ktlint, the API dump, and the
tests. Green before a push, every time.

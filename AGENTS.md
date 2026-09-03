# Working in this repo

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built.

The draft is a proposal, not a plan. Its job is to be **cheap to disagree
with**, so it stays short enough to read in one sitting:

- **One page.** Roughly 80 lines. A draft that runs longer is proposing too
  much at once — split it into two specs rather than writing more.
- **No prose padding.** Fill the template's headings and stop. Where a heading
  has nothing real under it, write "nothing" and move on.
- **Uncertainty goes under Open questions**, not into a hedged paragraph under
  Shape. Three or four questions in a first draft is healthy.
- **Options, not verdicts.** Where a design could go two ways, give each a
  sentence and recommend one. Do not silently pick.

Then stop and hand it over. Do not implement a spec nobody has edited and
committed: an unreviewed draft is still the agent's own opinion, which is the
thing this process exists to stop.

Before a spec exists: ask questions, read code, answer in chat. No code.

`specs/README.md` gives the layout and the lifecycle.

## One spec section per pull request

Pull requests are stacked. Each branch sits on the one before it and is
reviewable on its own.

- **Soft cap: 200 changed lines**, excluding generated sources and golden
  fixtures. Past that, split before writing code rather than after.
- **One spec section per PR.** A spec with four stack entries is four branches,
  not one branch with four commits.
- **Announce the split first.** Post the intended stack — branch name and one
  line each — and wait for a yes before the first commit.

### Working a stack

Set this once, so a rebase carries the branches above it:

```bash
git config rebase.updateRefs true
```

Branch from `origin/main`, not from a local `main` that may be behind, and
build bottom-up with each PR based on its parent:

```bash
git switch -c spec-0001-stream origin/main
gh pr create --base main --fill

git switch -c spec-0001-async-and-split        # branches off spec-0001-stream
gh pr create --base spec-0001-stream --fill
```

After review changes land on a lower branch, restack from the top of the stack
and push the whole chain:

```bash
git switch spec-0001-async-and-split
git rebase origin/main
git push --force-with-lease origin spec-0001-stream spec-0001-async-and-split
```

When the bottom PR merges, GitHub retargets its children onto `main` by itself.
Rebase once more so the diff shown is only that branch's own work.

## Comments

Comments record what the code cannot: the reason a thing is done the way it is.
They do not restate the code, and they are not essays.

**Write a comment when there is a why.** A surprising API, a constraint from a
dependency, a trade-off that was actually made, a bug that a naive version
reintroduces. One or two sentences.

**Do not write one when there is not.** If the signature and the body already
say it, say nothing.

### Rules

- **KDoc: one line by default.** Two or three only where the reason genuinely
  takes them. Reserve a multi-paragraph block for a decision the reader would
  otherwise undo — there should be very few in a file.
- **No worked examples in KDoc** unless the call is hard to get right from the
  signature. The README and `docs/` carry the tutorial; a code comment is not
  the place to teach the DSL twice.
- **No restating the code.** `/** The status. */ val status: Int` earns
  nothing.
- **No history.** "The alternative was…", "this used to…", "before this it
  was…" belongs in the commit message, not the source. Exception: naming a bug
  the comment exists to stop coming back.
- **No rhetorical framing.** Skip "Note the split of responsibilities", "which
  is the whole point", "and that is the difference that buys". State the fact.
- **Inline `//` notes are for the line below them**, not for paragraphs. If it
  runs past three lines, it is either KDoc or it is too long.

### Shape

```kotlin
// Yes — names a real constraint, once.
/** Pekko's `mapAsync` drops a null completion; this one dies instead. */
private fun <B : Any> guarded(stage: CompletionStage<B?>): CompletionStage<B> = ...

// No — restatement, then an essay about it.
/**
 * The completion stage, but with nulls turned into failures.
 *
 * `mapAsync` is not a map: a stage completing with null is quietly not pushed,
 * so a nullable lookup lifted into a future used to lose its element with no
 * trace, because the drop landed inside Pekko's own stage. ...
 */
```

Same rules in test sources. A test name should carry the claim; the KDoc above
it should not repeat the name in longer words.

## Imports

**No wildcard imports, anywhere, and no unused ones.** One line per name, so an
import block is an inventory of what a file uses and where each piece lives —
and so a reader of the documentation's examples can see exactly what to import.
Two detekt rules fail the build on either mistake, `WildcardImport` and
`UnusedImport`, and `.editorconfig` tells ktlint and the IDE the same thing so
an optimize-imports cannot put a star back.

The same holds in the docs and the examples: every complete example carries its
imports written out, and the `dependencies { }` block naming the modules it
needs, with real coordinates. A reader is copying into a project that has none of this in
scope.

## Layout

One library module, `dipper-core`: the Kotlin standard library, `pekko-stream`
and `arrow-core`, and nothing else. `NoOtherDependenciesTest` asserts its
runtime classpath. No `pekko-http`, no JSON library, no coroutines; a bridge
to any of those is a leaf module that carries the library itself.

A dependency added to core is a build failure, not a judgement call.

## Values, errors and effects

A `Stream` is a value: built, inspected and composed without a materializer,
and run once, at the end, by a call that names the system.

Errors a caller was promised are values in the type. `fail(e)` ends a stream
with its declared `E`, and `run` answers `Exit.Failed(e)`. Throwing is for
what nobody declared — a bug — and arrives as `Exit.Died(cause)`, never as a
failed `CompletionStage`.

**Nothing drops an element silently.** There is no `resume`, no supervision
attribute, and no operator that filters on its own; an element leaves a
pipeline through a named sink or not at all. A `null` from a stage is a
defect, and the element bound `A : Any` keeps it a compile error where Kotlin
can see it.

Do not wrap work in `runCatching` and map the result into a failure. That
produces a second error model beside the declared one.

Never add an `else` to a `when` over a sealed type. The missing branch is the
compiler naming a case that needs handling.

Public API returns read-only types. `var` is a last resort: a mutable
accumulator is allowed inside a builder that freezes it before returning, and
`FunctionalStyleTest` lists each file permitted one with its reason.

## Testing

Write the failing test first. A test written afterwards asserts what the
implementation does rather than what the description promised.

Test names are sentences in backticks. Kotest matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

- **Contract tests** through the public API, running real streams on a test
  actor system owned by a JUnit 5 extension, never started by hand.
- **Does-not-compile tests** for every claim the type system makes: a nullable
  `mapOrFail` body, a `Stream<E, A>` handed to `toSource()` with `E` unhandled.
- **Timing claims need a gate, not a sleep.** Assert that the first element
  arrives well before the last, rather than that a run took N seconds.

Kover has a floor of 90% on `check`.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

**Finish with `./gradlew spotlessApply`.** Formatting is a gate like any other,
and a change that is correct but unformatted fails the build for whoever runs
it next. Run it last, after the final edit, so nothing lands unformatted:

```bash
./gradlew spotlessApply && ./gradlew build
```

Four gates sit beyond the tests.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding | a suppression with no reason |
| `FunctionalStyleTest` | a new file allocates a mutable collection | an unexplained entry |
| Kover | aggregate line coverage under 90% | lowering the floor |
| `NoOtherDependenciesTest` | core grew a dependency | adding it to the allowlist |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- If a caller-visible behaviour changed, the README changed with it.
- No new dependency in `dipper-core`.

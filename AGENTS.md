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

GitHub tracks a stack itself, so the chain is one object rather than a set of
pull requests that happen to point at each other. Install the extension once:

```bash
gh extension install github/gh-stack
```

Start the stack on the branch the first entry lands on, then add one branch per
entry, bottom-up:

```bash
gh stack init spec-0003-descriptor
# ... write code, commit ...
gh stack add spec-0003-codec
# ... write code, commit ...
gh stack submit
```

`submit` pushes every branch, opens a pull request per entry with the right
base, and links them. `gh stack view` prints the chain and where each one is.

Where there is no terminal to prompt at — which is every agent — `submit` writes
the titles itself and opens all of them as drafts. Say so when handing the stack
over, or mark them ready one at a time with `gh pr ready`. Re-running
`gh stack submit --open` also marks them, and rewrites every description it
generated on the way through.

A change asked for on a lower entry is made there, not worked around above it:

```bash
gh stack checkout spec-0003-descriptor
# ... commit the change ...
gh stack rebase --upstack
gh stack push
```

GitHub's own *Rebase stack* button in the merge box does the same thing
server-side, and the commits it writes are unsigned. Prefer `gh stack rebase`,
which follows whatever signature configuration the machine has.

When the bottom pull request merges, one command catches the rest up:

```bash
gh stack sync --prune
```

Branches that already exist become a stack without being rebuilt:

```bash
gh stack init branch-one branch-two branch-three && gh stack submit
```

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
/** Pekko's `StatusCodes.get` throws for unregistered codes; `custom` does not. */
private fun statusOf(code: Int): StatusCode = ...

// No — restatement, then an essay about it.
/**
 * Pekko's own status where it knows one, and a custom status where it does not.
 *
 * `StatusCodes.get` is not a lookup: it is `int2StatusCode`, which throws for
 * anything not in the registry. So an endpoint that declared a perfectly legal
 * 419 used to document that status and then answer 500, because the throw
 * landed in the interpreter's own `exceptionally`. ...
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
needs, with real coordinates. `example/` generates neither by hand — a file
that grows an import grows a line in its block.

## Layout

Three modules. `lark` depends on `arrow-core` and on the JDK, and on nothing
else: no HTTP library, no JSON library, no coroutines, and virtual threads come
from `java.lang.Thread`. `lark-pekko` adds `pekko-actor` and only that: it
exists to hand a Pekko dispatcher to `lark` as the executor its forks run on,
and to await Pekko's stages from a virtual thread. `lark-stream` is
`Stream<E, A>` over Pekko Streams, and depends on `lark`, on `lark-pekko` —
`awaitExit` waits through its `await`, so one bridge cancels an abandoned stage
for a handler and for a run alike — on `pekko-stream` and on the Arrow that
arrives with `lark`, and on nothing else.

Each claim is a test. `NoOtherDependenciesTest` asserts the module's main
runtime classpath, in every module alike.

A dependency added to either is a build failure, not a judgement call.

## Values, errors and effects

Descriptions are values. There is no registry and nothing that has to run at
startup: `routes` is a `List<ServerEndpoint>` that can be split across files or
filtered. Prefer a value to a function when adding a feature.

Errors a caller was promised are values in the return type. `orFail` puts the
declared failure into the endpoint's type and `handledOrFail` takes a handler
returning `Outcome<E, A>`. Throwing is for what nobody declared — a decode
failure, a broken codec, a bug.

Do not wrap a handler in `runCatching` and map the result into a failure. That
produces a second error model beside the declared one.

In a lark handler, `raise` is the only way to leave with a declared failure.
A raise unwinds through the body as an exception, so a `catch` between it and
the boundary eats it: `catch (t: Throwable)` belongs in the one function that
completes the stage from the virtual thread, and detekt permits it nowhere
else.

A `Raise` scope never crosses a thread. Work forked from a handler answers
with an `Either`, and the handler binds it.

Never add an `else` to a `when` over a sealed type. The missing branch is the
compiler naming a case that needs handling in every interpreter.

Public API returns read-only types. A mutable accumulator is allowed inside a
builder that freezes it before returning. `FunctionalStyleTest` lists every
file permitted one, each with its reason; a new file needs an entry and a
reason, not a path added to get green.

The handler chain carries `CompletionStage<Any?>`. `suspend` cannot appear
anywhere in `lark` — `NoOtherDependenciesTest` would say so.

## Testing

Write the failing test first. Most claims here are claims about agreement
between the route, the document, the test client and the generated client, and
a test written afterwards asserts what the implementation does rather than what
the description promised.

Test names are sentences in backticks. Kotest matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Thread claims.** A test that says the body ran on a virtual thread reads
  `Thread.currentThread().isVirtual` inside the body and asserts on it
  afterwards; it does not count threads or time sleeps.

### An actor system belongs to the test class

`lark-pekko` and `lark-stream` tests need an `ActorSystem`. It is created in a
`companion object` from a config string and stopped in `@AfterAll`, never by
hand inside a test and never shared across classes — a system left running
holds threads that make the next suite's thread assertions lie.

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

Three gates sit beyond the tests. Each exists because a claim in the README
would otherwise be unverified.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding | a suppression with no reason |
| `NoOtherDependenciesTest` | anything but core and Arrow on the runtime classpath | widening the allow-list |
| Kover | line coverage under 90% | lowering the floor |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- No new dependency in `lark`.
- The README reflects the change, or the change is invisible from outside.

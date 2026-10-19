# Working a stack

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

An agent has no terminal to prompt at, so `submit` does what `--auto` does: it
writes the titles itself and opens every pull request as a draft. Ask for them
open instead, or the stack is handed over in a state nobody reviews:

```bash
gh stack submit --open
```

A stack already submitted is opened one at a time with `gh pr ready`, which
leaves the descriptions alone.

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

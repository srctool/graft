---
tags: [graft, feature, compare]
status: seedling
milestone: G1
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 21 - Compare Branches

"What's in `feature` that isn't in `main`?" Use it before opening a PR, or to review what an agent did on a branch.

## Picking what to compare

Two pickers, **A** (base) and **B** (compare). Each accepts any branch (local or remote), tag, commit, PR head, or a worktree's working copy.

Shortcuts:
- right-click a branch → **Compare with main**, or **with its upstream**;
- select two commits in the graph → **Compare**.

## Views

| View | Command |
|---|---|
| Commits in B, not in A | `git log A..B` |
| Commits in A, not in B | `git log B..A` |
| Merge base | `git merge-base A B` |
| **Changes B introduces** (default) | `git diff A...B`: from the merge base, which is what a PR shows |
| Direct difference | `git diff A B`: the trees as they are now |
| Files changed with line stats | `--numstat` |
| Would B merge cleanly into A? | `git merge-tree --write-tree A B`; lists conflicting files without touching anything |

The header reads "B is 5 ahead and 12 behind A".

## Actions

Cherry-pick selected commits onto A; **create a pull request** from B into A ([Pull Requests](09%20-%20Pull%20Requests.md)); export as a patch ([Patches](28%20-%20Patches.md)); open a file's diff side by side.

## Worktree compare

Compare the working copies of two worktrees, including uncommitted changes. Useful when two agents worked on the same task in parallel ([Agent Worktrees](23%20-%20Agent%20Worktrees%20and%20Review.md)). It uses snapshot commits of each worktree ([Local History](14%20-%20Local%20History.md)), diffed like any two commits.

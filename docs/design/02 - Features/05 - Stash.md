---
tags: [graft, feature, stash]
status: seedling
milestone: G4
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 05 - Stash

Plain `git stash`, fully compatible with the CLI. For named, independent change sets see [Shelves](06%20-%20Shelves.md).

| Action | Command |
|---|---|
| Stash all | `git stash push [-u] [-m msg]` |
| Stash selection | `git stash push -m msg -- <paths>` (files); hunk-level via a temporary index and `git stash push --staged` |
| Keep index | `--keep-index` |
| Apply / Pop | `git stash apply/pop [--index] stash@{n}` |
| Drop / Clear | `git stash drop` / `clear` (clear asks, and is undoable for 30 days via the op log, which keeps the hashes) |
| Branch from stash | `git stash branch <name> stash@{n}` |
| View | diff of `stash@{n}` against its base, including untracked part (`stash@{n}^3`) |

Stashes appear on the graph at their base commit. Rename is done as drop + store with a new message (`git stash store -m`).

## Edge cases

| Case | Decided behavior |
|---|---|
| Apply conflicts | conflict flow; pop keeps the stash until resolved |
| Stash made in another worktree | visible everywhere (`refs/stash` is shared), labelled with its branch |

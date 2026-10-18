---
tags: [graft, feature, worktrees]
status: seedling
milestone: G5
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 07 - Worktrees

`git worktree` lets one repository have several checked-out working directories, each on its own branch. Graft makes them visible and easy to switch between.

## Panel

From `git worktree list --porcelain -z`, plus a status per worktree:

| Column | Source |
|---|---|
| Name / path | path (relative to the main worktree when nearby) |
| Branch or detached commit | `branch` / `HEAD` lines |
| Dirty | `git -C <path> status --porcelain=v2` (cheap, background) |
| Ahead/behind upstream | `for-each-ref` on its branch |
| Locked / prunable | `locked`, `prunable` lines with reason |
| Main | the first entry; can't be removed |

## Actions

| Action | Command |
|---|---|
| Create from new branch | `git worktree add -b <branch> <path> <start>` |
| Create from existing / remote branch | `git worktree add <path> <branch>` (`--track` for remote) |
| Create from a PR | fetch the PR head, then add (see [Pull Requests](09%20-%20Pull%20Requests.md)) |
| Create detached | `--detach` |
| Open | in a new Graft tab or window; also "open in editor / terminal / file manager" |
| Move | `git worktree move` |
| Lock / unlock | `git worktree lock --reason` / `unlock` |
| Remove | `git worktree remove [--force]` (force only after showing dirty files, offer to shelve them first) |
| Prune | `git worktree prune` (lists stale entries first) |
| Repair | `git worktree repair` after moving folders by hand |

Default path for new worktrees is configurable: `../<repo>.worktrees/<branch>` by default.

## On the graph and elsewhere

- A folder badge marks every commit checked out in some worktree.
- Branch labels checked out in another worktree show its name; checking them out here offers **Open that worktree**.
- Shelves are shared: unshelve into any worktree ([Shelves](06%20-%20Shelves.md)).
- Repository tabs group worktrees of one repo under one header.

## Edge cases

| Case | Decided behavior |
|---|---|
| Worktree folder deleted by hand | shown as prunable with Prune / Repair |
| Worktree on removable or network drive | shown with a "missing" state while unavailable, not pruned automatically |
| Submodules inside worktrees | each worktree has its own submodule checkouts; panel shows that |
| Bare repo + worktrees layout | supported; the bare repo has no working-copy panel |

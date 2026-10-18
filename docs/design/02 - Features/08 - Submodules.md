---
tags: [graft, feature, submodules]
status: seedling
milestone: G5
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 08 - Submodules

## Panel

Tree of submodules (recursive) from `git submodule status --recursive` and `.gitmodules`:

| State | Meaning | Shown |
|---|---|---|
| Not initialized | `-` prefix | grey, **Init & update** |
| In sync | recorded commit = checked-out commit | normal |
| Pointer differs | `+` prefix: the submodule moved | "moved: 3 commits ahead of recorded", **Stage pointer** / **Reset to recorded** |
| Dirty | uncommitted changes inside | dirty dot |
| Conflict | `U` prefix | conflict marker; pick ours/theirs commit |
| URL out of sync | `.gitmodules` URL ≠ `.git/config` | **Sync** |

## Actions

| Action | Command |
|---|---|
| Init / update | `git submodule update --init [--recursive]` |
| Update to remote tip | `git submodule update --remote [--merge/--rebase]` |
| Sync URLs | `git submodule sync --recursive` |
| Add | `git submodule add [-b branch] <url> <path>` |
| Remove | `git submodule deinit -f <path>` + `git rm <path>` (+ clean `.git/modules/<path>`, asked) |
| Change branch / URL | edit `.gitmodules` via `git config -f .gitmodules` |
| Open | opens the submodule as its own repository session in a tab with a breadcrumb back to the parent |

## Integration

- Diffs of a submodule pointer show the commit list between old and new pointer, clickable.
- Fetch and pull offer `--recurse-submodules`; setting per repository.
- The graph of the parent marks commits that changed a submodule pointer.

## Edge cases

| Case | Decided behavior |
|---|---|
| Recorded commit not on the submodule's remote | warn "commit not pushed in submodule" before pushing the parent |
| Submodule detached HEAD (normal) | shown as normal; offer "checkout branch" for working inside it |
| Nested submodules | full tree, lazily expanded |
| Relative URLs (`../lib.git`) | resolved against the parent remote, also for forks |

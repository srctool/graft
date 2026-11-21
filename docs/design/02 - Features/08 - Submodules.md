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

## Review in the parent

Graft is mainly a place to review changes, so a submodule's changes are read from the parent, as in the source control view of an editor. Opening the submodule in its own tab (see Actions) stays available but is not needed to see what changed.

| Where | What shows |
|---|---|
| Changes panel ([Working Copy](04%20-%20Working%20Copy%20and%20Commit.md)) | the parent's lists first, then one **section per initialized submodule** (nested submodules nest, expanded on demand), each with its own Staged and Unstaged lists, stage, unstage, discard and commit. A section's header shows the submodule's name, branch (or "detached at abc1234") and ahead/behind. Uninitialized submodules show one row with **Init & update**. |
| The parent's pointer | a change in the parent's list, "moved: 3 commits ahead of recorded", for the case where the submodule's checked-out commit differs from the recorded one. **Stage pointer** is the action. |
| Commit details and [Compare Branches](21%20-%20Compare%20Branches.md) | a pointer change expands into the **files changed inside the submodule** between the old and new pointer, read from the submodule's own repository; selecting a file opens its diff as for any file. If the submodule isn't initialized or lacks the commits, the row says so and offers Init & update or Fetch. |
| Graph | commits that moved a pointer carry a submodule mark (as in Integration above) |

- Each section is its own repository: its own write queue ([ADR-3](../03%20-%20Project/02%20-%20Decisions.md), one mutating operation at a time per repository), op log, [Local History](14%20-%20Local%20History.md) and commit guards. Committing in a submodule never commits the parent.
- The usual order is: commit in the submodule, then stage the parent's pointer and commit it. The panel offers **Commit submodule, then stage pointer** as one step, which asks before it touches the parent.
- The Changes panel is built as a **list of working copies** (the parent, then submodules) from G2, even while it shows only the parent, so nothing is rebuilt when submodules arrive.

### In G2 (before this panel exists)

A submodule appears in the parent's Changes panel as **one row**, like a file: the path, a marker for "moved" (the pointer differs from the recorded commit), "modified content" or "untracked content" (from the status flags), and an **Open** action that opens the submodule's own tab. Its inner files, its own commit box and the pointer drill-down arrive in G5.

## Edge cases

| Case | Decided behavior |
|---|---|
| Recorded commit not on the submodule's remote | warn "commit not pushed in submodule" before pushing the parent |
| Submodule detached HEAD (normal) | shown as normal; offer "checkout branch" for working inside it |
| Nested submodules | full tree, lazily expanded; as nested sections in the Changes panel |
| A submodule with uncommitted changes when the parent commits its pointer | the commit dialog warns that the pointer points at a commit without the submodule's uncommitted changes |
| Relative URLs (`../lib.git`) | resolved against the parent remote, also for forks |

---
tags: [graft, feature, changelists]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 15 - Changelists

JetBrains' partner to shelves: split uncommitted changes into **named groups** ("login fix", "debug logging", "agent: refactor"). Then commit, shelve or discard one group at a time ([ADR-15](../03%20-%20Project/02%20-%20Decisions.md)).

## Mode

Changelists are an **optional mode per repository**, and they replace the Staged/Unstaged lists:

| Mode | Changes panel | Git index |
|---|---|---|
| Staging area (default) | Staged / Unstaged, as in [Working Copy](04%20-%20Working%20Copy%20and%20Commit.md) | the user's |
| Changelists | one section per changelist | used only internally while committing |

The two don't mix well (a hunk would be "staged" and "in list B" at once), so JetBrains makes them exclusive too. Switching from staging mode to changelists moves staged changes into a "Staged" changelist.

### Setting: "Changes view"

| Level | Where | Values |
|---|---|---|
| App default | Settings → Working copy → Changes view | **Staging area** (default) / Changelists |
| Per profile | Profile settings, "inherit from Default" by default | same |
| Per repository | repo settings, and a toggle in the changes panel header | Inherit / Staging area / Changelists |

The most specific level wins: repository, then profile, then app. The repo choice is stored in `$GIT_COMMON_DIR/graft/state.json`, so all worktrees of a repo use the same view (each worktree keeps its own lists).

Switching modes:
- **Staging area → Changelists:** staged changes become a "Staged" changelist; the rest go to Default.
- **Changelists → Staging area:** Graft asks whether to stage the active changelist or leave everything unstaged. List names and commit drafts are kept, so switching back restores them.

## Model

- Each changelist has a name, an optional description, and a **commit message draft**.
- Changes are assigned **by file or by hunk**. Hunks are tracked by their content plus surrounding lines, and re-anchored when the file changes around them.
- One changelist is **active**: new changes land there. A **Default** changelist always exists.
- Stored per worktree in `$GIT_DIR/graft/changelists.json`. It's per worktree because each worktree has its own uncommitted changes.

## Actions

| Action | How |
|---|---|
| Move file / hunk to another list | drag, or right-click → Move to |
| Set active | click the radio on a list; new changes go there |
| **Commit changelist** | temp index = HEAD + the list's hunks → `git commit` with that index; the list's draft message prefilled |
| Shelve changelist | one shelf named after it; unshelving recreates the list ([Shelves](06%20-%20Shelves.md)) |
| Discard changelist | after confirmation; [Local History](14%20-%20Local%20History.md) snapshot first |
| Create patch from changelist | [Patches](28%20-%20Patches.md) |
| Fixup into commit | [Fixup and Absorb](26%20-%20Fixup%20and%20Absorb.md) |

Tip for agents: create an "Agent" changelist and make it active before letting an agent work. Its edits are then kept apart from your own, and you can review them as one group.

## Edge cases

| Case | Decided behavior |
|---|---|
| A hunk can't be re-anchored (heavily edited) | it moves to the active list, with a notice |
| File renamed | assignment follows the rename (`-M` detection) |
| Branch switch | lists stay; assignments for changes that vanished are dropped |
| Changes made outside Graft | land in the active list on the next refresh |
| Commit of a list fails a hook | nothing is committed; the list is unchanged |

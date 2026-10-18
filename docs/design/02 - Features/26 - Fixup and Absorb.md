---
tags: [graft, feature, history]
status: seedling
milestone: G9
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 26 - Fixup and Absorb

Fix earlier commits on a branch without an interactive-rebase session.

## Fixup into a commit

Right-click a file, hunk or changelist → **Fixup into…**:
1. Graft lists the commits on the branch since its base (or upstream).
2. The user picks one.
3. Graft runs `git commit --fixup=<sha>` with only those changes. A `fixup!` commit appears, grouped under its target in the graph.

Variants:
- **Amend into…:** fixup, then squash right away.
- **Reword…:** `--fixup=reword:<sha>` (message only).

## Squash fixups

**Squash fixups** runs `git rebase -i --autosquash <base>` non-interactively (`GIT_SEQUENCE_EDITOR=true`), so every `fixup!` commit melts into its target. A preview of the resulting commit list comes first.

## Absorb

**Absorb** sends each staged hunk to the commit it belongs to, automatically, like `git absorb`:
1. For each hunk, Graft blames its lines at HEAD, limited to the branch's own commits.
2. If one commit last touched all those lines, the hunk becomes a fixup for that commit.
3. A hunk with no match, or several, stays staged and is reported.
4. A preview table (hunk → target commit) can be edited before running. Then fixups are created and squashed.

## Safety

- Commits already pushed: a warning that a force push will follow (`--force-with-lease`).
- Rewritten commits are recorded in the op log, so the whole absorb or squash is undoable.
- Never crosses a merge commit; stops at the branch base.

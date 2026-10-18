---
tags: [graft, feature, branches]
status: seedling
milestone: G3
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 02 - Branches and Tags

## Branches

| Action | Command | Notes |
|---|---|---|
| Create | `git branch <name> <start>` / `git switch -c` | optional checkout, optional track; name validated with `git check-ref-format --branch` |
| Checkout | `git switch <name>` | dirty tree: offer **shelve**, **stash**, or carry changes over |
| Checkout remote branch | `git switch --track origin/x` | creates the local branch |
| Rename | `git branch -m` | offers to rename the remote branch too |
| Delete | `git branch -d` / `-D` | `-D` only after showing unmerged commits; offers remote delete |
| Set upstream | `git branch -u` | |
| Merge | `git merge [--no-ff] [--squash]` | preview: commits and files that would come in, conflicts predicted with `git merge-tree --write-tree` |
| Rebase | `git rebase <onto>` | same preview; interactive rebase in a list editor (pick, reword, edit, squash, fixup, drop, reorder by drag) |
| Cherry-pick | `git cherry-pick <commits>` | multi-select from the graph |
| Revert | `git revert` | |
| Reset | `git reset --soft/--mixed/--hard` | `--hard` lists what is lost and is undoable via op log |

The branch already checked out in another worktree can't be checked out here; the menu shows **Open worktree** instead ([Worktrees](07%20-%20Worktrees.md)).

## Tags

Create lightweight or annotated (optionally signed), delete local/remote, push one or all (`git push origin <tag>`), checkout as detached HEAD, create a GitHub release from a tag (provider capability).

## Conflicts

When merge, rebase, cherry-pick, revert, stash apply or unshelve stops on conflicts:
- the banner shows the operation and progress (`rebase 3/7`);
- conflicted files list with **Ours / Theirs / Merge…**;
- the merge tool is a built-in three-pane editor (base, ours, theirs, result), or the user's configured `mergetool`;
- **Continue / Skip / Abort**.

## Edge cases

| Case | Decided behavior |
|---|---|
| Branch name collides with a tag | warn; refs shown fully qualified |
| Deleting the current branch | not offered; checkout another first |
| Rebase of commits already pushed | warn that a force push will be needed |
| `rerere` enabled | resolutions reused; shown in the log |

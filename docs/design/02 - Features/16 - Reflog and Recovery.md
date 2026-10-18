---
tags: [graft, feature, reflog, safety]
status: seedling
milestone: G1 (view), G3 (restore)
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 16 - Reflog and Recovery

Graft's operation log only sees what Graft did. When the terminal or an AI agent runs `reset --hard`, a bad rebase, or deletes a branch, the **reflog** is how commits come back. Graft makes it readable.

## Timeline

One timeline per repository, merging:
- the HEAD reflog and every branch's reflog (`git log -g --format=… -z`, `git reflog show <ref>`);
- Graft's operation log, matched by time and new hash.

Each entry shows time, ref, old → new commit, the reflog message (`reset: moving to HEAD~3`, `rebase (finish)`), and its **source**: Graft, or "outside Graft" (terminal, IDE, agent).

## Actions on an entry

| Action | Command |
|---|---|
| Show commit / diff | as in the graph |
| Checkout | detached HEAD at that commit |
| Create branch here | `git branch <name> <sha>` |
| **Restore branch to here** | `git update-ref` / `reset`, after a [Local History](14%20-%20Local%20History.md) snapshot; undoable |
| Cherry-pick | `git cherry-pick <sha>` |

## Finding lost commits

"Find lost commits" runs `git fsck --unreachable --no-reflogs` and lists commits no ref or reflog points to: dropped stashes, deleted branches whose reflog expired, abandoned rebases. Each shows its message, date and diff, and can be restored as a branch.

## External rewrite banner

The file watcher sees refs change. When a branch moves to a commit that isn't a descendant of where it was, a banner appears:

> `main` was rewritten outside Graft (was `a1b2c3d`, 4 commits no longer on it). **Show** · **Restore** · Dismiss

The same happens when a branch is deleted outside Graft while it had unpushed commits.

## Edge cases

| Case | Decided behavior |
|---|---|
| Reflog disabled (`core.logAllRefUpdates=false`, bare repos) | timeline shows only Graft's op log; lost-commit scan still works |
| Reflog expiry | default Git expiry (90 days, 30 for unreachable); shown in settings, editable via [Git Config Editor](29%20-%20Git%20Config%20Editor.md) |
| `git gc --prune=now` ran | unreachable objects are gone; Graft says so instead of failing |
| Many entries | paged, filterable by ref and source |

---
tags: [graft, feature, shelves]
status: seedling
milestone: G4
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 06 - Shelves

JetBrains IDEs have a **shelf**: put some changes aside under a name, keep working, bring them back later, in any order, partly or fully. Git has only the stash stack. Graft adds shelves on top of plain Git ([ADR-5](../03%20-%20Project/02%20-%20Decisions.md)).

## Shelf vs. stash

| | Stash | Shelf |
|---|---|---|
| Structure | a stack (`stash@{0}`, `{1}`…), indexes shift | a set of named, independent items with stable ids |
| Granularity | whole tree, or paths | files, hunks or lines (the same `ChangeSelection` as commit) |
| Restore | all of it | all, some files, or some hunks; **apply** (keep the shelf) or **unshelve** (remove what was restored) |
| Keep changes in the working copy | `--keep-index` only | "Shelve and keep" copies without removing |
| Across worktrees | shared | shared, and designed for it: shelve in one worktree, unshelve in another |
| Visible to the git CLI | yes | yes, as refs (`git log refs/graft/shelves/<id>`) |
| Export | — | `.patch` file, importable by Graft, `git apply` and JetBrains |

## Storage

Each shelf is a **commit object** pointed to by a ref:

```
refs/graft/shelves/<ulid>        → shelf commit
    tree    = base tree + the shelved changes (incl. untracked files)
    parent  = the base commit (HEAD when shelved)
    message = shelf name + description
    trailers: Graft-Shelf-Version: 1
              Graft-Branch: feature/login
              Graft-Worktree: ../app-hotfix
              Graft-Untracked: path/a, path/b
```

Why commits and not patch files:
- objects are content-addressed and gc-safe while the ref exists;
- binary files and renames are exact;
- unshelving can do a real **three-way merge** against the base, so it still applies after the branch moved on;
- refs under `refs/` (not `refs/worktree/`) are shared by all worktrees.

`refs/graft/*` is never pushed by a normal `git push`. Pushing shelves to a remote is a later, explicit option.

## Operations

| Operation | How |
|---|---|
| **Shelve** | temp index: `GIT_INDEX_FILE=tmp git read-tree HEAD` → apply the selection (`git apply --cached`, `git update-index --add` for untracked) → `write-tree` → `commit-tree -p HEAD` → `update-ref refs/graft/shelves/<id>`; then reverse-apply the selection to the working tree (unless "keep") |
| **Unshelve all / part** | diff `shelf^..shelf` limited to the chosen files/hunks → `git apply --3way` onto the working tree; if fully restored, move the shelf to the recycle bin |
| **Apply** (keep shelf) | same, without removing the shelf |
| **Unshelve to new branch** | `git switch -c <name> <shelf base>` then unshelve |
| **Rename / edit description** | new commit with the same tree and parent, ref updated |
| **Merge two shelves** | combine trees with `git merge-tree`; conflicts reported, not written |
| **Delete** | ref moves to `refs/graft/shelves-trash/<id>`; purged after 30 days |
| **Export / import** | `git diff --binary shelf^ shelf > name.patch`; import creates a shelf from a patch on HEAD |
| **Auto-shelve** | optional: on checkout with a dirty tree, shelve as "Auto: before switching to X" and offer to restore when coming back (JetBrains-like) |

## UI

- Left panel section **Shelves**, newest first, grouped by branch. Each shows name, base commit, file count, age.
- Opening a shelf shows its files and diff with checkboxes per file and hunk, and **Unshelve selected** / **Apply selected**.
- Drag a shelf onto a worktree in the Worktrees panel: unshelve there.
- On the graph: dashed node off the base commit (toggle).

## Edge cases

| Case | Decided behavior |
|---|---|
| Base commit no longer exists locally | the shelf commit keeps it alive (it's the parent), so diffing still works |
| Unshelve conflicts | normal conflict markers in files, listed in the conflict panel; the shelf is kept until resolved |
| File was deleted on the current branch | conflict "deleted by us"; user picks |
| Shelving a submodule pointer change | stored as a gitlink in the tree, restored like other changes |
| Very large untracked files | warn above 50 MB; they become blobs in the object store |
| Another client runs `git gc --prune=now` | shelves survive: they are referenced |
| User runs `git push --mirror` | would push shelves; documented |

## Done when

- [ ] Shelve 2 hunks of a file in worktree A, unshelve them in worktree B on a branch that has moved 10 commits ahead, with a clean three-way merge.
- [ ] Export a shelf and apply it with plain `git apply`.

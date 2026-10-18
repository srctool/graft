---
tags: [graft, feature, local-history, safety]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 14 - Local History

Automatic snapshots of the working tree, like JetBrains' Local History. Restore a file, or the whole tree, as it was minutes ago, even if it was never committed or stashed. This matters most when an AI agent edits files: if it breaks or deletes uncommitted work, the reflog can't help, and Local History can ([ADR-14](../03%20-%20Project/02%20-%20Decisions.md)).

## Storage

Snapshots reuse the [shelf](06%20-%20Shelves.md) mechanism:
- Each snapshot is a **commit of the whole working tree**: tracked plus untracked, ignored files excluded. Graft builds it with a temporary index (`GIT_INDEX_FILE=tmp git add -A` → `write-tree` → `commit-tree`), so the user's index is never touched.
- Snapshots form one chain per worktree: `refs/graft/history/<worktree-id>` points at the newest, and each snapshot's parent is the previous one.
- **Deduplicated:** if the tree equals the previous snapshot's tree, no snapshot is made. Unchanged files cost nothing, since Git stores each blob once.
- Never pushed, like everything under `refs/graft/`.

## When a snapshot is taken

| Trigger | Default |
|---|---|
| Files changed, then quiet for a moment | at most every 5 min while changes keep coming |
| Before a Graft operation that changes files | checkout, reset, discard, pull, rebase, merge, clean, unshelve, stash apply |
| External branch rewrite detected | see [Reflog and Recovery](16%20-%20Reflog%20and%20Recovery.md) |
| AI agent hook (optional) | a Claude Code `PreToolUse` hook on file-editing tools runs `graft snapshot --quiet`, so there's a snapshot before every agent edit, even when Graft isn't open |
| Manual | "Snapshot now", with an optional label ("before refactor"); `graft snapshot -m "…"` |

Snapshots run only while Graft is open, or when something calls `graft snapshot`. The CLI works without the app running.

## Retention

| Age | Kept |
|---|---|
| < 24 h | every snapshot |
| 1–7 days | one per hour |
| 7–30 days | one per day |
| > 30 days | dropped (labelled snapshots kept until deleted) |

Thinning rewrites the internal chain; dropped objects are later removed by normal `git gc`. Limits: files over 10 MB are skipped (configurable), and there's a total budget of 1 GB of unique snapshot objects per repo, oldest unlabelled dropped first.

## UI

- **File history:** right-click a file → Local History shows a timeline of its versions, a diff against the current file, and **Restore file** or **Restore hunk**.
- **Tree history:** a timeline of snapshots with changed files per snapshot. **Restore all** first takes a snapshot of the current state, so the restore itself is undoable.
- **Deleted files:** "Show deleted files" lists files that existed in a snapshot but are gone now.
- **Search:** find a file path or text across snapshots.

## Edge cases

| Case | Decided behavior |
|---|---|
| Secrets in untracked, non-ignored files | included like any other file; local only; "Exclude from local history" patterns in settings |
| Huge generated folders not ignored (`build/`) | warn once suggesting a `.gitignore` entry ([Ignore and Clean](20%20-%20Ignore%20and%20Clean.md)) |
| Submodules | each submodule has its own history; the parent snapshot stores only the pointer |
| Worktree removed | its history ref kept 30 days, then dropped |
| Disk full / object write fails | snapshotting pauses with a warning; never blocks the user's operation |

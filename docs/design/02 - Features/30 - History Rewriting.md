---
tags: [graft, feature, history, danger]
status: seedling
milestone: G10
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 30 - History Rewriting

Rare, dangerous operations that change many commits. They're always backed up and always explained ([ADR-18](../03%20-%20Project/02%20-%20Decisions.md)).

## Engine

`git filter-repo` (the tool Git's own docs recommend over `filter-branch`) when installed; Graft offers to install it. Without it, only the operations that plain rebase can do are offered.

## Operations

| Operation | How | Try first |
|---|---|---|
| **Change author / committer** on selected commits | rebase with `exec git commit --amend --author=… --no-edit` (or `filter-repo --mailmap` for the whole history) | a `.mailmap` entry, which fixes how names display without rewriting anything |
| **Remove a file or folder from all history** | `filter-repo --invert-paths --path <p>` | if it's a secret: rotate it first |
| **Replace text in all history** (a leaked secret) | `filter-repo --replace-text` with the expressions given | rotate the secret |
| **Extract a folder into its own repository** | `filter-repo --subdirectory-filter <dir>` on a fresh clone | |
| **Strip large files** | `filter-repo --strip-blobs-bigger-than 10M`, or migrate to LFS (`git lfs migrate import`) | |

## Safety

1. **Backup:** every ref is copied to `refs/graft/backup/<timestamp>/…` and a [Local History](14%20-%20Local%20History.md) snapshot is taken. **Restore backup** puts every ref back.
2. **Preview:** affected commits are counted and shown; Graft explains that every hash after the first changed commit changes.
3. **Pushed history:** a warning that it needs a force push of every rewritten branch, and that collaborators must re-clone or reset. It lists open PRs that will break.
4. **Secrets:** rewriting doesn't un-leak a pushed secret, because forks, clones and caches keep it. Graft's dialog leads with **rotate the secret**, and links GitHub's guidance for removing cached views.
5. filter-repo refuses non-fresh clones; Graft passes `--force` only after the backup exists.

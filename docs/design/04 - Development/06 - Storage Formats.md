---
tags: [graft, development, storage]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 06 - Storage Formats

## 1. Why

Graft keeps its own data inside users' repositories (shelves, snapshots, changelists, stacks) and in its app folders (profiles, caches). Repositories outlive app versions and get opened by other Graft versions on other machines. Every format is therefore versioned, documented here, and migrated carefully.

## 2. Shape

| Data | Where | Format | Version marker |
|---|---|---|---|
| Shelves | `refs/graft/shelves/<ulid>` (+ `shelves-trash/`) | commit; metadata in trailers | `Graft-Shelf-Version: 1` trailer |
| Local history | `refs/graft/history/<worktree-id>` | chain of commits | `Graft-History-Version: 1` trailer |
| History-rewrite backups | `refs/graft/backup/<timestamp>/…` | ref copies | none (plain refs) |
| Changelists | `$GIT_DIR/graft/changelists.json` (per worktree) | JSON | `"version": 1` |
| Repo UI state | `$GIT_COMMON_DIR/graft/state.json` | JSON | `"version": 1` |
| Guard exceptions | `$GIT_DIR/graft/guard-allow.json`; `.graft/guard.toml` (committed) | JSON / TOML | `version = 1` |
| Stack parents | git config `branch.<name>.graftParent`, `graftParentTip` | git config | key names are the format |
| Profiles | app data `profiles/<id>/profile.json`, `profile.gitconfig`, `workspace.json`, `settings.json` | JSON, git config | `"version": 1`; a `# graft-profile-version: 1` comment |
| Managed `~/.gitconfig` block | between `# >>> graft managed` markers | git config | marker line carries `v1` |
| App settings | app data `settings.json` | JSON | `"version": 1` |
| Cache | OS cache dir, SQLite | SQLDelight schema | SQLDelight migrations; **safe to delete** |

## 3. API

### 3.1 Rules

1. **Every format has a version**, and a reader checks it first.
2. **Readers accept the versions they know**, and ignore unknown *fields* (forward-compatible additions).
3. **Unknown newer version:** read-only. Graft shows "created by a newer Graft (needs ≥ X)" and never rewrites it.
4. **Writes are atomic:** write a temp file, `fsync`, rename. Refs are updated with `git update-ref` and the old value (compare-and-swap).
5. **Migrations** run once at first start of a new version. A backup is taken first (files copied to `…/backups/<version>/`, refs to `refs/graft/backup/migration-<n>/`). The migration is recorded in the op log.
6. **Format changes need an ADR**, plus a fixture of the old format and a migration test ([Testing Strategy](03%20-%20Testing%20Strategy.md)).

### 3.2 Shelf commit (v1)

```
tree <tree>
parent <base commit>
author Graft <graft@localhost> <time>
committer Graft <graft@localhost> <time>

<shelf name>

<description>

Graft-Shelf-Version: 1
Graft-Branch: <branch or "(detached)">
Graft-Worktree: <path relative to the main worktree>
Graft-Untracked: <path>, <path>
Graft-Changelist: <name, if shelved from a changelist>
```

The author is a fixed Graft identity, not the user's, so shelf commits never carry personal data into a repository that might be mirrored.

## 4. Build steps

1. A `FormatVersion` helper and atomic file writer in `core-data` (G2).
2. Shelf and history commit codecs with golden fixtures (G2, G4).
3. The migration runner with backups (before G8).

## 5. Edge cases

| Case | Decided behavior |
|---|---|
| Corrupt JSON file | renamed to `*.corrupt-<time>`, defaults used, a warning shown; never silently overwritten |
| Two Graft versions on one repo at once (e.g. two machines via a shared folder) | refs use compare-and-swap; JSON files are rewritten only after re-reading |
| Repo mirrored with `git push --mirror` | `refs/graft/*` would be pushed; documented in Shelves |
| User deletes `.git/graft/` | changelists and UI state reset; shelves and history are refs, so they're unaffected |

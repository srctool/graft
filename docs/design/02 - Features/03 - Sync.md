---
tags: [graft, feature, sync]
status: seedling
milestone: G3
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 03 - Sync (Fetch, Pull, Push)

## Actions

| Action | Command | Default |
|---|---|---|
| Fetch | `git fetch --prune [--all] [--recurse-submodules=on-demand]` | all remotes, also in the background |
| Pull | `git pull --ff-only` / `--rebase` / `--no-rebase` | follows `pull.rebase` / `pull.ff`; else asks once per repo |
| Push | `git push [-u] <remote> <branch>` | sets upstream on first push |
| Force push | `git push --force-with-lease --force-if-includes` | never plain `--force` ([ADR-6](../03%20-%20Project/02%20-%20Decisions.md)) |
| Push tags | `git push <remote> <tag>` / `--follow-tags` | |
| Delete remote branch | `git push <remote> --delete <branch>` | |

Progress (`--progress`) is parsed from stderr into a progress bar with cancel.

## Remotes

Add, rename, remove, edit URL (fetch and push separately), and **Add fork remote** from the provider (lists forks of the repository).

## Toolbar

`Pull ▾ · Push ▾ · Fetch` with the ahead/behind count of the current branch. Pushing a branch with no open PR shows **Create pull request** right after ([Pull Requests](09%20-%20Pull%20Requests.md)).

## Edge cases

| Case | Decided behavior |
|---|---|
| Push rejected (non-fast-forward) | explain, offer Pull (rebase) then Push, or force-with-lease |
| Auth failure | askpass dialog; "use provider account token" if available; for SSH, open the [SSH](12%20-%20SSH.md) wizard on "Permission denied (publickey)" |
| Protected branch rejection | show the server message verbatim |
| Pull with a dirty tree | `--autostash` offered, or shelve first |
| Offline | fetch fails quietly in background; manual actions show the error |

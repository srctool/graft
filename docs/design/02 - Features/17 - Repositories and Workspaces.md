---
tags: [graft, feature, repositories]
status: seedling
milestone: G1
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 17 - Repositories and Workspaces

How repositories get into Graft and how many of them are managed at once.

## Home screen

Shown in a new window or tab:
- **Recent and pinned** repositories, with branch, dirty state and ahead/behind.
- **Workspaces.**
- **Search** across all known repositories.
- **Missing repositories** (moved or deleted) appear greyed out, with **Locate…** and **Remove**.

## Getting a repository in

| Action | Details |
|---|---|
| **Clone** | from a URL, or browse the repos of the profile's accounts (yours, your orgs', starred). The folder defaults to the profile's clone folder. Options: depth, single branch, recurse submodules, partial clone (`--filter=blob:none`). The **profile is chosen first**, so the clone gets the right identity and key from its first commit ([Profiles](11%20-%20Profiles.md)) |
| **Init** | `git init` with default branch name; optional `.gitignore` template, license file, README, first commit; optionally create the GitHub repo and push |
| **Open / add existing** | pick a folder; also drag a folder onto the window |
| **Scan folder** | finds every repository under a folder (e.g. `~/Projects`) and adds them, optionally as a workspace |

## Tabs and windows

One repository (or worktree, or submodule) per tab, several tabs per window, several windows. Tabs remember their pane layout. Worktrees of one repository are grouped under one tab header ([Worktrees](07%20-%20Worktrees.md)).

## Workspaces

A **workspace** is a named group of repositories, stored per profile.
- **Local workspace:** repositories picked by hand or by a folder scan.
- **Provider workspace:** "all repositories of org `acme`". Missing ones can be cloned in one click.

| Workspace action | How |
|---|---|
| Overview table | per repo: branch, dirty, ahead/behind, open PRs, CI status of the current branch |
| Fetch all | `git fetch --prune` in every repo, in parallel (max 4) |
| Pull all | `git pull --ff-only` where it's clean and fast-forwardable; others listed with the reason |
| Open all | in tabs |
| Filter the "My Work" dashboard by workspace | [My Work](18%20-%20My%20Work%20and%20Notifications.md) |

## Command line

The `graft` CLI (installed by [Onboarding](22%20-%20Onboarding.md)):

```bash
graft              # open the repository of the current folder
graft open ~/app   # open a path
graft clone <url>  # clone with the matching profile, then open
```

If Graft is running, the CLI asks the running instance to open a tab. Otherwise it starts the app.

## Edge cases

| Case | Decided behavior |
|---|---|
| Opening a subfolder of a repo | opens the repository root, scrolled to that folder in the file tree |
| Opening a folder that isn't a repo | offers Init or Clone into it |
| "dubious ownership" (`safe.directory`) error | explained in a dialog with **Trust this folder** ([Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md)) |
| Same repo reached through a symlink | deduplicated by real path |

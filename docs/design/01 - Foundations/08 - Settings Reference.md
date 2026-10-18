---
tags: [graft, foundations, settings]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 08 - Settings Reference

Every user setting in one place. **Scope** says where it can be set; the most specific one wins: **repo → profile → app**. Settings marked *git* are git config and edited through the [Git Config Editor](../02%20-%20Features/29%20-%20Git%20Config%20Editor.md), not here. Storage: [Storage Formats](../04%20-%20Development/06%20-%20Storage%20Formats.md).

## General

| Setting | Default | Scope | Note |
|---|---|---|---|
| Theme | follow OS (Graft Light / Dark) | app, profile | [UI Design](06%20-%20UI%20Design.md) |
| High contrast | follow OS | app | [Accessibility](05%20-%20Accessibility.md) |
| Density | standard (24 px rows); comfortable 28 px | app, profile | |
| Language | follow OS (English, Indonesian) | app | |
| Keymap | JetBrains; presets GitKraken, VS Code | app, profile | |
| Avatars | on (provider, then Gravatar) | app | [Threat Model](07%20-%20Threat%20Model.md) privacy |
| Remote images in Markdown | off | app | |
| Default clone folder | `~/Projects` | profile | [Repositories](../02%20-%20Features/17%20-%20Repositories%20and%20Workspaces.md) |
| Default worktree folder | `../<repo>.worktrees/<branch>` | profile, repo | [Worktrees](../02%20-%20Features/07%20-%20Worktrees.md) |
| External editor / terminal / merge tool | detected; none | app | |

## Working copy

| Setting | Default | Scope | Note |
|---|---|---|---|
| Changes view | Staging area | app, profile, repo | [Changelists](../02%20-%20Features/15%20-%20Changelists.md) |
| Inline diff size limit | 2 MB | app | [Working Copy](../02%20-%20Features/04%20-%20Working%20Copy%20and%20Commit.md) |
| Ignore whitespace in diffs | off | app | |
| Auto-shelve on checkout with a dirty tree | off (ask) | profile, repo | [Shelves](../02%20-%20Features/06%20-%20Shelves.md) |
| Shelf recycle bin retention | 30 days | app | |
| Conventional commit prefixes | off | repo | |

## Local history

| Setting | Default | Scope | Note |
|---|---|---|---|
| Enabled | on | app, repo | [Local History](../02%20-%20Features/14%20-%20Local%20History.md) |
| Snapshot interval | 5 min | app | |
| Max file size | 10 MB | app | |
| Budget per repo | 1 GB | app | |
| Exclude patterns | none | repo | |

## Sync

| Setting | Default | Scope | Note |
|---|---|---|---|
| Background fetch | every 5 min; off on battery | app, repo | [Sync](../02%20-%20Features/03%20-%20Sync.md) |
| Pull mode | follow `pull.rebase` / `pull.ff` (*git*), else ask once | repo | |
| Fetch submodules | on demand | repo | [Submodules](../02%20-%20Features/08%20-%20Submodules.md) |
| Offer provider token over HTTPS | off | profile | [Provider Model](04%20-%20Provider%20Model.md) |

## Profiles, SSH, agents

| Setting | Default | Scope | Note |
|---|---|---|---|
| Matching rules (path, remote), pinned repos | none | profile | [Profiles](../02%20-%20Features/11%20-%20Profiles.md) |
| Write profiles to git config | ask on first rule | app | [Terminal and AI Agents](../02%20-%20Features/13%20-%20Terminal%20and%20AI%20Agents.md) |
| SSH mode | system SSH | profile | [SSH](../02%20-%20Features/12%20-%20SSH.md) |
| SSH never prompt (`BatchMode`) | off | profile | |
| Sign commits | from git config (*git*) | profile | |
| `gh` shim | off | app | |
| Agent launchers | Claude Code, Cursor presets | app | [Agent Worktrees](../02%20-%20Features/23%20-%20Agent%20Worktrees%20and%20Review.md) |
| Agent instructions file | off | repo | |

## Safety

| Setting | Default | Scope | Note |
|---|---|---|---|
| Commit guards | on | repo | [Commit Guards](../02%20-%20Features/19%20-%20Commit%20Guards.md) |
| Large-file warn / block | 10 MB / 100 MB | app, repo | |
| Large-repo performance card | on | app | [Git Engine](03%20-%20Git%20Engine.md) |
| Reflog expiry | Git default (*git*) | repo | [Reflog and Recovery](../02%20-%20Features/16%20-%20Reflog%20and%20Recovery.md) |

## Hosting and notifications

| Setting | Default | Scope | Note |
|---|---|---|---|
| PR list / open PR / CI polling | 60 s / 20 s / 10 s, while visible | app | [CI and Actions](../02%20-%20Features/24%20-%20CI%20and%20Actions.md) |
| Desktop notification reasons | review requested, CI failed, approved / changes requested, merged, mentioned | profile | [My Work](../02%20-%20Features/18%20-%20My%20Work%20and%20Notifications.md) |
| Do not disturb | follow OS focus mode | app | |
| Issue branch name pattern | `<number>-<slug>` | repo | [Issues](../02%20-%20Features/10%20-%20Issues.md) |
| Agent branch prefix | `agent/` | repo | |

## Updates and privacy

| Setting | Default | Scope | Note |
|---|---|---|---|
| Check for updates automatically | on (off for package-manager installs) | app | [Release and Updates](../04%20-%20Development/05%20-%20Release%20and%20Updates.md) |
| Update channel | stable | app | |
| Crash reports | off (opt-in) | app | [ADR-7](../03%20-%20Project/02%20-%20Decisions.md) |

---
tags: [graft, project]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 01 - Roadmap

Order: read-only first (safe to dogfood daily), then local writes, then the network, then hosting. Each milestone is usable on its own.

| Milestone | Content | Notes | Done when |
|---|---|---|---|
| **G0 Setup + spike** | repo, Gradle modules, CI on 3 OSes, `GitRunner`, a throwaway graph spike on a 500k-commit repo | [Architecture](../01%20-%20Foundations/02%20-%20Architecture.md), [Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md), [Project Setup](../04%20-%20Development/01%20-%20Project%20Setup.md), [CI](../04%20-%20Development/04%20-%20CI.md) | spike meets the graph performance target, confirming ADR-1 |
| **G1 Read-only browser** | home screen, clone/init/scan, tabs, workspaces, `graft open`; commit graph with author-type filter, commit details, diff viewer, left panel (branches, remotes, tags, stashes), search; compare branches; reflog timeline (view only); basic onboarding (Git check, identity); graph semantics for screen readers | [Commit Graph](../02%20-%20Features/01%20-%20Commit%20Graph.md), [Repositories and Workspaces](../02%20-%20Features/17%20-%20Repositories%20and%20Workspaces.md), [Compare Branches](../02%20-%20Features/21%20-%20Compare%20Branches.md), [Reflog and Recovery](../02%20-%20Features/16%20-%20Reflog%20and%20Recovery.md), [Onboarding](../02%20-%20Features/22%20-%20Onboarding.md) | the author uses it daily instead of `git log --graph` |
| **G2 Working copy** | status, stage/unstage file/hunk/line, discard, commit, amend, op log + undo; local history; changelists; commit guards; ignore and clean | [Working Copy](../02%20-%20Features/04%20-%20Working%20Copy%20and%20Commit.md), [Local History](../02%20-%20Features/14%20-%20Local%20History.md), [Changelists](../02%20-%20Features/15%20-%20Changelists.md), [Commit Guards](../02%20-%20Features/19%20-%20Commit%20Guards.md), [Ignore and Clean](../02%20-%20Features/20%20-%20Ignore%20and%20Clean.md) | a week of commits made only in Graft; a file deleted by an agent restored from local history |
| **G3 Branching + sync** | branches/tags CRUD, merge, rebase, interactive rebase, cherry-pick, revert, reset, conflict resolution; fetch/pull/push, remotes, credentials; profiles (identity, matching rules, workspace) written as git config includes; SSH (system mode, key generation, agent, host keys, test, managed keys); `graft` CLI with `whoami`, `doctor`, `credential`, `snapshot`, `guard`; reflog restore and the external-rewrite banner; full onboarding | [Branches and Tags](../02%20-%20Features/02%20-%20Branches%20and%20Tags.md), [Sync](../02%20-%20Features/03%20-%20Sync.md), [Profiles](../02%20-%20Features/11%20-%20Profiles.md), [SSH](../02%20-%20Features/12%20-%20SSH.md), [Terminal and AI Agents](../02%20-%20Features/13%20-%20Terminal%20and%20AI%20Agents.md) | full feature-branch cycle without the terminal; Claude Code in a Work repo commits and pushes as Work while the Graft window is on Personal |
| **G4 Stash + Shelves** | stash UI; shelves with partial shelve/unshelve, recycle bin, export/import, auto-shelve | [Stash](../02%20-%20Features/05%20-%20Stash.md), [Shelves](../02%20-%20Features/06%20-%20Shelves.md) | shelves Done-when checks pass |
| **G5 Worktrees + Submodules** | worktree panel and actions; submodule panel, nested sessions | [Worktrees](../02%20-%20Features/07%20-%20Worktrees.md), [Submodules](../02%20-%20Features/08%20-%20Submodules.md) | manage a repo with 3 worktrees and nested submodules from Graft only |
| **G6 GitHub** | provider SPI, GitHub auth (device flow, PAT, `gh`) with accounts per profile, SSH key upload to GitHub, `gh` shim with one gh login per profile, PR list/detail/review/create/merge, issues, PR badges on graph; My Work dashboard and notifications | [Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md), [Pull Requests](../02%20-%20Features/09%20-%20Pull%20Requests.md), [Issues](../02%20-%20Features/10%20-%20Issues.md), [My Work and Notifications](../02%20-%20Features/18%20-%20My%20Work%20and%20Notifications.md) | review and merge a real PR end to end in Graft; a review request arrives as a desktop notification |
| **G7 Public beta** | open the repo publicly, docs site, installers, crash reporting (opt-in), first external contributors | [Open Source](03%20-%20Open%20Source.md) | 3 OS installers on GitHub Releases; CONTRIBUTING tested by one outside contributor |
| **G8 1.0** | signing + notarization, auto-update, accessibility pass, performance pass, i18n (English, Indonesian) | [Accessibility](../01%20-%20Foundations/05%20-%20Accessibility.md) | Overview "Done when" all checked |
| **G9 1.1 Agent workflows** | agent worktrees and review; CI and Actions; stacked branches and PRs; fixup, autosquash, absorb; agent CLI commands (shelf, changelist, history, review, stack) and the `graft mcp` server; `graft-cli` package; Claude Code plugin (MCP + snapshot and guard hooks); "Add to Cursor" button | [Agent Worktrees and Review](../02%20-%20Features/23%20-%20Agent%20Worktrees%20and%20Review.md), [CI and Actions](../02%20-%20Features/24%20-%20CI%20and%20Actions.md), [Stacked Branches](../02%20-%20Features/25%20-%20Stacked%20Branches.md), [Fixup and Absorb](../02%20-%20Features/26%20-%20Fixup%20and%20Absorb.md), [CLI](../02%20-%20Features/31%20-%20CLI.md), [MCP Server](../02%20-%20Features/32%20-%20MCP%20Server.md) | three Claude Code agents run in parallel worktrees; their work is reviewed hunk by hunk and lands as a 3-PR stack; an agent addresses review comments by itself through `graft_review_feedback` |
| **G10 1.2 Power tools** | bisect; patches; git config editor; history rewriting; large-repo performance card | [Bisect](../02%20-%20Features/27%20-%20Bisect.md), [Patches](../02%20-%20Features/28%20-%20Patches.md), [Git Config Editor](../02%20-%20Features/29%20-%20Git%20Config%20Editor.md), [History Rewriting](../02%20-%20Features/30%20-%20History%20Rewriting.md) | a leaked test secret is removed from a scratch repo's history and restored from the backup |

## Later

| Item | Notes |
|---|---|
| GitLab provider | first after GitHub; MRs, pipelines |
| Gitea / Forgejo (Codeberg), Bitbucket, Azure DevOps | same SPI |
| Jira, Linear | issue-only providers |
| Pushing shelves to a remote | `refs/graft/shelves/*` refspec, opt-in |
| Git LFS UI, sparse checkout, partial clone UI | |
| Blame view with history walking | |
| Built-in terminal | maybe |
| Plugin API | commands, panels, providers from outside the repo; in-process plugins must be GPL-3.0-compatible (ADR-10) |
| Optional AI helpers | commit message, PR description; bring-your-own key |

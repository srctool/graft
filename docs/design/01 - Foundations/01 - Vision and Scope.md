---
tags: [graft, foundations]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 01 - Vision and Scope

## Vision

A Git client that shows the **whole state of a repository at once**: every branch, tag, stash, shelf, worktree, submodule and open pull request. Everyday operations are one click, and dangerous ones are always undoable or confirmed.

## Users

| User | Needs |
|---|---|
| Developer on a team using GitHub | review PRs, keep branches in sync, juggle several tasks |
| Developer switching contexts often | shelves and worktrees instead of `WIP` commits |
| Maintainer of a repo with submodules | see which submodule is out of date or dirty |
| Git learner | see what a command does to the graph before running it |

## Goals (1.0)

1. Feature parity with GitKraken's core: graph, branches, tags, sync, stash, worktrees, submodules, PRs, issues.
2. **Shelves**, as in JetBrains IDEs, working with plain Git and shared across worktrees.
3. GitHub (github.com and GitHub Enterprise Server) as the first provider, behind a contract that fits GitLab, Bitbucket, Gitea/Forgejo and Azure DevOps.
4. Fast on large repositories: the graph streams, and nothing blocks the UI thread.
5. Free and open source, with no account needed for local use.

## Non-goals (1.0)

| Not doing | Why |
|---|---|
| Reimplementing Git (libgit2, JGit) | behavior drifts from the CLI; worktrees, hooks, signing and LFS break ([ADR-2](../03%20-%20Project/02%20-%20Decisions.md)) |
| A code editor | Graft opens files in the user's editor |
| A built-in terminal | later, maybe; the user has one |
| Accounts, cloud sync, telemetry by default | privacy; local-first |
| Team features (shared workspaces, cloud patches) | GitKraken's paid tier; out of scope |
| AI commit messages | later, as an optional plugin |

## Principles

1. **Git is the source of truth.** Graft keeps no state that Git doesn't, except caches and its own shelves (which are Git refs too).
2. **Show the command.** Every action can show the `git` command it runs, and an operation log keeps them.
3. **Safe by default.** `--force-with-lease`, never `--force`. A destructive action shows what will be lost. Undo uses the reflog.
4. **Keyboard first.** Every action has a command-palette entry and a shortcut.
5. **Provider-neutral core.** Nothing outside `provider-github` says "GitHub".

## Competitors, for reference

GitKraken, Fork, Tower, Sublime Merge, SmartGit, Sourcetree, GitHub Desktop, GitButler, Gittyup, SourceGit, lazygit, JetBrains Git integration, VS Code + GitLens.

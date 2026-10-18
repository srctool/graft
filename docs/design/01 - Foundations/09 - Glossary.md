---
tags: [graft, foundations, glossary]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 09 - Glossary

One meaning per term, used the same way in code, UI text, docs and agent prompts. **Code name** is the type or identifier in Graft's code. Terms that only exist in Graft are marked ★.

## Graft's own concepts

| Term | Meaning | Code name | Not to confuse with |
|---|---|---|---|
| **Shelf** ★ | a named, independent set of changes put aside; restorable fully or partly, in any worktree. Stored as a commit under `refs/graft/shelves/` | `Shelf` | **stash**: Git's stack of changes; a shelf is not a stack |
| **Shelve / Unshelve / Apply** ★ | put changes on a shelf / restore them and remove what was restored / restore and keep the shelf | `shelve()`, `unshelve()`, `apply()` | stash pop / apply |
| **Changelist** ★ | a named group of uncommitted changes (files or hunks) in the working copy; one is *active* | `Changelist` | a commit, a shelf |
| **Changes view** ★ | the setting choosing **Staging area** or **Changelists** mode for the changes panel | `ChangesView` | |
| **Snapshot** ★ | an automatic copy of the working tree in local history | `Snapshot` | a shelf (deliberate) or a commit |
| **Local history** ★ | the timeline of snapshots of a worktree | `LocalHistory` | the reflog, commit history |
| **Profile** ★ | one identity you work as: git name and email, signing key, accounts, SSH mode, workspace | `Profile` | an account (one login at a provider) |
| **Account** | one login at a provider (a GitHub user), belonging to a profile | `Account` | profile |
| **Matching rule** ★ | a path or remote pattern that assigns repositories to a profile | `ProfileRule` | |
| **Pinned repository** ★ | a repository assigned to a profile by hand (an `include` in `.git/config`) | `pinnedProfile` | pinned in the UI (kept at the top of a list) |
| **Workspace** ★ | a named group of repositories, per profile | `Workspace` | a worktree |
| **Repository session** ★ | a repository, worktree or submodule opened in Graft: one service, watcher and write queue | `RepositorySession` | a tab (the UI showing a session) |
| **Commit guard** ★ | a check run before commit or push (secrets, sensitive files, size, conflict markers) | `Guard`, `Finding` | Git hooks (guards can *run as* hooks) |
| **Stack** | a chain of branches, each built on the previous; the parent is stored in `branch.<name>.graftParent` | `Stack` | the stash stack |
| **Restack** | rebase every branch of a stack onto its updated parent | `restack()` | rebase (one branch) |
| **Agent worktree** ★ | a worktree created for an AI agent's task, shown in the Agents panel | `AgentTask` | |
| **Agent launcher** ★ | a command template that starts an agent (`claude "{prompt}"`) | `AgentLauncher` | |
| **Review mode** ★ | the live hunk-by-hunk review of an agent worktree | `ReviewSession` | a PR review |
| **Review feedback** ★ | comments left in review mode, readable by agents | `ReviewComment` | PR review comments |
| **Op log** (operation log) ★ | Graft's record of every mutating command, with argv and refs before and after; powers Undo | `OpLog` | the reflog (Git's own, sees every tool) |
| **External rewrite** ★ | a branch moved to a non-descendant commit by something other than Graft | `ExternalRewrite` | |
| **Doctor** ★ | the check of what identity, key and account any tool would use in a folder | `graft doctor` | |
| **Managed block** ★ | the marked section of `~/.gitconfig` that Graft owns | `ManagedGitConfigBlock` | |
| **Managed key** ★ | an SSH key Graft passes for one profile via `core.sshCommand` | `SshMode.Managed` | system SSH |

## Hosting (provider-neutral)

| Term | Meaning | Code name |
|---|---|---|
| **Provider** | a hosting service integration (GitHub first) | `HostingProvider` |
| **Change request** | a pull request (GitHub, Bitbucket, Azure) or merge request (GitLab). UI text uses the provider's own word | `ChangeRequest` |
| **Review** | an approval, a request for changes, or a comment review on a change request | `Review` |
| **Check** | one CI status or check run on a commit | `Check` |
| **Pipeline** | a CI run made of jobs (GitHub Actions workflow run) | `Pipeline` |
| **Capability** | a feature a provider supports, so the UI can hide the rest | `Capability` |
| **Host** | a provider instance: `github.com` or a GitHub Enterprise Server address | `Host` |

## Git terms as Graft uses them

| Term | Meaning in Graft |
|---|---|
| **WIP** | the uncommitted changes of the current worktree, shown as the top row of the graph |
| **Worktree** | a checked-out working directory of a repository; the first one is the **main worktree** |
| **Stash** | Git's stash, unchanged |
| **Upstream** | the remote branch a local branch tracks |
| **Ahead / behind** | commits on the local branch not on its upstream / the other way round |
| **Base** (of a branch) | the commit where a branch left its parent or target branch (merge base) |
| **Lane** | a vertical line in the graph drawing |
| **Ref** | a branch, tag, remote branch, stash, or Graft ref (`refs/graft/*`) |

## UI words

| Use | Avoid |
|---|---|
| Shelve, Unshelve | Park, Save for later |
| Discard changes | Revert changes (revert is a git command that creates a commit) |
| Pull request (on GitHub) | Change request in UI text (code only) |
| Profile | Identity, Persona |
| Snapshot | Backup (backup means history-rewrite backups) |

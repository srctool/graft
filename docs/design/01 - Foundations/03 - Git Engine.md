---
tags: [graft, foundations, git]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 03 - Git Engine

Graft drives the user's installed `git` ([ADR-2](../03%20-%20Project/02%20-%20Decisions.md)). The engine is a typed wrapper: each command is a class that builds arguments and parses output into `core-model` types.

## Why the CLI

| Option | Problem |
|---|---|
| libgit2 (via JNI) | no worktree add with all options, partial submodule support, no hooks, no SSH config parity, no commit signing parity, no LFS |
| JGit | slower on large repos, limited worktree support, behavior differs from `git` |
| **git CLI** | always matches what the user gets in the terminal; costs a process per call and output parsing |

The process cost is handled by batching (`git cat-file --batch`, one `for-each-ref` for all refs) and caching by commit hash.

## Runner

```kotlin
interface GitRunner {
    suspend fun <T> run(cmd: GitCommand<T>, cwd: Path, input: Source? = null): Result<T>
    fun <T> stream(cmd: StreamingGitCommand<T>, cwd: Path): Flow<T>
}
```

Every process gets:

| Setting | Why |
|---|---|
| `LC_ALL=C`, `-c core.quotepath=false` | stable, unescaped output |
| `GIT_TERMINAL_PROMPT=0` | never block waiting on a TTY |
| `GIT_ASKPASS` / `SSH_ASKPASS` = Graft's helper, `SSH_ASKPASS_REQUIRE=force` | credential, passphrase and host-key prompts appear as Graft dialogs |
| Nothing for profiles | the repo's [profile](../02%20-%20Features/11%20-%20Profiles.md) comes from git config includes, the same for Graft and every other tool; `-c` overrides only in the no-config-writes fallback |
| `GIT_EDITOR=<graft helper>` | commit messages and rebase todo lists open in Graft, not vim |
| `GIT_OPTIONAL_LOCKS=0` for reads | background `status` doesn't fight the user's terminal for `index.lock` |
| `-c color.ui=never` | no ANSI codes |
| Hooks run as normal | pre-commit output streams into the operation log |

## Output parsing

Always the machine formats:

| Need | Command |
|---|---|
| Commits for the graph | `git log --topo-order --format=%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ct%x00%s%x00 -z --all` (streamed) |
| Refs | `git for-each-ref --format=... refs/heads refs/remotes refs/tags refs/stash refs/graft` |
| Status | `git status --porcelain=v2 -z --branch --show-stash` |
| Worktrees | `git worktree list --porcelain -z` |
| Submodules | `git submodule status --recursive` + `git config -f .gitmodules -z --list` |
| Diff | `git diff --no-color --no-ext-diff -M -z --patch` (and `--numstat` for the file list) |
| Objects in bulk | `git cat-file --batch` kept open per session |

Parsers are pure functions over byte streams, tested with fixtures recorded from real repositories (including non-UTF-8 paths and messages).

## Operation log

Every mutating command is recorded: time, repository, the exact argv, exit code, stderr, and the refs before and after. It powers:
- **Show command** next to every action;
- **Undo**: restore refs from the "before" snapshot (and reflog) for commit, amend, reset, rebase, merge, branch delete, cherry-pick.

## Git version

Minimum `2.38` (porcelain worktree listing with `-z`, `merge-tree --write-tree`). On start Graft checks `git --version`, and on Windows/macOS offers to use a bundled Git if the system one is missing or too old.

## Large repositories

When a repository is big (over 100k files, or `git status` over 500 ms), a **Performance** card offers these. Each one writes repo config, so none is turned on silently:

| Setting | Effect |
|---|---|
| `core.fsmonitor=true` | Git's built-in file-system monitor daemon (macOS, Windows); on Linux, a Watchman hook if installed |
| `core.untrackedCache=true` | faster untracked-file scanning |
| `feature.manyFiles=true` | index v4 and other many-files defaults |
| `git maintenance start` | background commit-graph, prefetch, loose-object and pack maintenance |
| `git commit-graph write --reachable --changed-paths` | faster graph loading and path-limited log |

## Edge cases

| Case | Decided behavior |
|---|---|
| `index.lock` exists | wait 2 s, then show "another Git process is running" with a "Remove stale lock" button if no git process is alive |
| A hook fails | show the hook output; the operation is not retried silently; offer `--no-verify` explicitly |
| Repo is huge (Linux kernel) | graph streams in pages; uses `commit-graph` if present and offers to write it |
| Partial clone / shallow clone | graph marks the shallow boundary; missing blobs are fetched on demand by git itself |
| "dubious ownership" (`safe.directory`) | a dialog explains the folder is owned by another user, with **Trust this folder** (`git config --global --add safe.directory <path>`); never adds `*` |
| GPG/SSH signing needs a pinentry | handled by the user's agent; Graft only shows the waiting state |
| Windows paths and CRLF | paths are passed as argv, never through a shell; `core.autocrlf` respected |

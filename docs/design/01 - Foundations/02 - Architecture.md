---
tags: [graft, foundations, architecture]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 02 - Architecture

## Stack ([ADR-1](../03%20-%20Project/02%20-%20Decisions.md), [ADR-9](../03%20-%20Project/02%20-%20Decisions.md))

| Concern | Choice |
|---|---|
| Language | Kotlin (JVM 21) |
| UI | Compose Multiplatform Desktop + Material 3 styled as Fluent 2 (same tokens as Camouflage's Fluent skin), always used through Graft's own `ui-kit` module ([UI Design](06%20-%20UI%20Design.md); later maybe Camouflage) |
| Presentation | AndroidX `lifecycle-viewmodel` (multiplatform) with a small `GraftViewModel<State, Event, Effect>` base (later: Sinew presentation) |
| Errors | Graft's own `Result` + `GraftError` with codes (later: Sinew `SinewException`) |
| Network | Ktor client in `core-network`: auth header, ETag cache, retry, redacting logs (later: `sinew-network`) |
| Git | the `git` executable, `>= 2.38` ([Git Engine](03%20-%20Git%20Engine.md)) |
| Hosting APIs | Ktor client; GitHub GraphQL v4 + REST v3 |
| Local storage | SQLite (SQLDelight) for caches and app state; JSON for settings |
| Secrets | OS keychain: macOS Keychain, Windows Credential Manager, Linux Secret Service (libsecret) |
| Diff and syntax | a line diff from `git diff`; word diff computed in-app; syntax highlighting from TextMate grammars |
| DI | constructor injection, wired by hand in `app-desktop` (Koin optional) |
| Packaging | Compose Gradle plugin (`jpackage`) or Conveyor: `.dmg`, `.msi`, `.deb`, `.rpm`, AppImage/Flatpak |

## Modules

```mermaid
flowchart BT
    model["core-model<br/>Commit, Ref, Status, Shelf, Worktree…"]
    git["core-git<br/>GitRunner, parsers, commands"]
    repo["core-repo<br/>RepositoryService, watcher, op log"]
    prov["core-provider<br/>Provider SPI, ChangeRequest, Issue"]
    net["core-network<br/>Ktor client factory, auth, ETag, retry"]
    gh["provider-github"]
    kit["ui-kit<br/>GraftButton, GraftList…, GraftViewModel"]
    data["core-data<br/>SQLite cache, settings, secrets"]
    graph["feature-graph"]
    wc["feature-workingcopy"]
    shelf["feature-shelves"]
    wt["feature-worktrees"]
    sub["feature-submodules"]
    cr["feature-changerequests"]
    iss["feature-issues"]
    app["app-desktop<br/>windows, menus, wiring"]
    cli["app-cli<br/>graft CLI + graft mcp (native image)"]

    git --> model
    repo --> git
    prov --> model
    gh --> prov & net
    data --> model
    graph & wc & shelf & wt & sub --> repo
    cr & iss --> prov
    graph & wc & shelf & wt & sub & cr & iss --> data
    graph & wc & shelf & wt & sub & cr & iss --> kit
    app --> graph & wc & shelf & wt & sub & cr & iss & gh
    cli --> repo & data & gh
```

Rules:
- `core-*` never imports Compose.
- Features never import each other. Shared UI lives in `ui-kit`.
- Features never import `androidx.compose.material3` directly; only `ui-kit` does (lint rule). This is the seam where the toolkit can change later ([ADR-9](../03%20-%20Project/02%20-%20Decisions.md), [ADR-20](../03%20-%20Project/02%20-%20Decisions.md)).
- Only `app-desktop` and `app-cli` know concrete providers. `app-cli` never imports Compose; it talks to a running app over a local socket ([CLI](../02%20-%20Features/31%20-%20CLI.md)). CI fails an edge that isn't in this graph.

## Layers

| Layer | Holds | Example |
|---|---|---|
| Engine | runs `git`, parses output into models | `GitRunner.run(LogCommand(...))` |
| Service | one per repository, combines commands, owns caches and the watcher | `RepositoryService.graph()`, `.shelve(selection)` |
| View model | Event → Action → State + Effect on `GraftViewModel` | `GraphViewModel` |
| UI | Compose screens using only `ui-kit` components | `GraphPane` |

## Repository session

Opening a repository creates a `RepositorySession`:
- one `RepositoryService` and one file watcher on `.git/` and the working tree;
- a **write queue**: Git mutations run one at a time per repository (Git holds `index.lock` anyway), reads run in parallel;
- a `StateFlow<RepoSnapshot>` (HEAD, refs, status, operation in progress) every pane observes.

Each worktree and each opened submodule is its own session. They share the object store, so caches keyed by commit hash are shared.

## Threading

- UI on the Compose main thread only.
- `git` processes on `Dispatchers.IO`, with a cap per repository (default 4 readers).
- Parsing streams line by line, so the first graph rows render before `git log` finishes.
- Every operation is cancellable: cancelling kills the process tree.

## Refresh

| Trigger | Refresh |
|---|---|
| Change in `.git/HEAD`, `.git/refs/**`, `packed-refs` | refs, graph decorations |
| Change in `.git/index` or working tree | status (debounced 300 ms) |
| `.git/MERGE_HEAD`, `rebase-merge/`, `CHERRY_PICK_HEAD` appear | operation banner |
| Window gains focus | everything that is stale |
| Timer (default 5 min, off on battery) | background `fetch --prune` |

## Error model

A sealed `GraftError` with a code `module-layer-function-type`, for example `GIT-E-PUSH-REJ` (push rejected, non-fast-forward). The code format is the same as Sinew's, so switching to `SinewException` later is a one-to-one mapping. Git failures keep `stderr` and the exit code; the UI shows a readable message, the code and a "Show output" button.

## Storage locations

| What | Where |
|---|---|
| App settings, window state, recent repos | OS app data dir (`~/Library/Application Support/Graft`, `%APPDATA%\Graft`, `$XDG_CONFIG_HOME/graft`) |
| Cache (provider data, graph layout) | OS cache dir; safe to delete |
| Profiles (identity, rules, workspace, preferences) | app data dir `profiles/<id>/` |
| Tokens, SSH passphrases | OS keychain only |
| Managed SSH keys | `~/.ssh/graft_<profile>_ed25519` (so the terminal can use them too) |
| Shelves | inside the repository, as refs `refs/graft/shelves/*` ([Shelves](../02%20-%20Features/06%20-%20Shelves.md)) |
| Local history snapshots | refs `refs/graft/history/<worktree-id>` ([Local History](../02%20-%20Features/14%20-%20Local%20History.md)) |
| History-rewrite backups | refs `refs/graft/backup/<timestamp>/*` |
| Changelists | `$GIT_DIR/graft/changelists.json`, per worktree ([Changelists](../02%20-%20Features/15%20-%20Changelists.md)) |
| Stack parents | git config `branch.<name>.graftParent` ([Stacked Branches](../02%20-%20Features/25%20-%20Stacked%20Branches.md)) |
| Commit-guard exceptions | `$GIT_DIR/graft/guard-allow.json` (local) or `.graft/guard.toml` (committed, optional) |
| Per-repo UI state (hidden branches, pinned) | `$GIT_COMMON_DIR/graft/state.json` |

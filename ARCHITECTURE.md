# Architecture

A one-page map of Graft's code, for contributors and coding agents. The full design is in [`docs/design/`](docs/design/00%20-%20Overview.md). This page says where things are and which rules never bend.

## Bird's-eye view

Graft is a desktop Git client. It shows a repository's whole state (graph, branches, worktrees, shelves, pull requests) and changes it by running the user's own `git` executable. There's no Git library inside. Everything Graft stores is either a Git ref inside the repository (shelves, snapshots) or a small file in the app's folders.

```
app/        :app:desktop  (window, wiring, app services)      :app:cli  (graft CLI + MCP, no Compose)
                 │                                                 │
module/          ▼                                                 │
  navigation   :module:navigation                                  │
                 │                                                 │
  feature        ▼                                                 │
               :module:feature:*   (screens, view models, own use cases)
                 │                                                 │
  presentation   ├──► :module:presentation   (app-level state)     │
  domain         ├──► :module:domain         (shared use cases)    │
  data           ├──► :module:data:git | :data:app | :data:hosting ◄── :data:hosting-github
                 │            │                                    │
  core           └──► :module:core:ui (theme)                      ▼
                      :module:core:git (GitRunner) · network · database · security · preference
                      models · exception (Sinew) · notification · configuration
                                  │
                                  ▼
                            git executable
```

## Code map

The shape comes from the author's architecture board and matches Sinew's App Architecture (ADR-24). Everything but the apps lives under `module/` (ADR-25).

| Module | What lives there | Must not |
|---|---|---|
| `:app:desktop` | startup, hand-wired DI, the root Camouflage provider, windows and menus, platform integration, the app services (repository session and watcher, background fetch, auto-snapshot, inbox poller) | hold business screens |
| `:app:cli` | the `graft` command and `graft mcp` (GraalVM native image) | depend on Compose modules; change profiles or config; push |
| `:module:navigation` | center-pane routes and tabs | be imported by features |
| `:module:feature:*` | one area each: screens, view models, the area's own use cases | import another feature |
| `:module:presentation` | app-level state split by case (`WindowState`, `OperationsState`, `InboxState`, `ProviderState`) | import Compose |
| `:module:domain` | use cases shared by two or more features (created when first needed) | import Compose |
| `:module:data:git` | git-backed repositories: commits, refs, working copy, stash, shelves, local history, changelists, worktrees, submodules, sync; `GitSandbox` test fixtures | call git other than through `GitRunner` |
| `:module:data:app` | profiles, settings, repositories and workspaces | store secrets outside `SecretStore` |
| `:module:data:hosting` | provider-neutral repository interfaces and models (`ChangeRequest`, `Issue`, `Pipeline`, …), the contract test kit | mention any concrete provider |
| `:module:data:hosting-github` | the GitHub implementation (GraphQL + REST) | leak GitHub types outside the module |
| `:module:core:ui` | the theme (Carbon, Atom One), Graft-only shared components, the `Localizer` | contain feature logic |
| `:module:core:git` | `GitRunner`: process control, environment, write queue; the `GitException` family | call git through a shell |
| `:module:core:network` | the configured `sinew-network` client, token source, ETag cache | log secrets |
| `:module:core:database`, `:module:core:security`, `:module:core:preference` | the SQLite cache, `SecretStore` on the OS keychain, versioned settings files | |
| `:module:core:models`, `:module:core:exception` | shared domain models; the error-code registry and text redaction | depend on a business module |
| `:module:core:notification`, `:module:core:configuration` | desktop notifications; app folders and platform paths | |

The allowed edges are listed in `config/module-graph.txt`, and `./gradlew check` fails on any other.

## Invariants

These hold everywhere. Breaking one is a bug, even if tests pass.

1. **Git only through `GitRunner`**, with argv lists and machine-readable output (`-z`, porcelain v2). Never a shell, never JGit or libgit2.
2. **One mutating git operation at a time per repository.** Reads run in parallel without optional locks.
3. **Nothing destroys work without a way back:** a snapshot, backup ref or op-log entry comes first. Force pushes are always `--force-with-lease --force-if-includes`.
4. **Secrets only in `SecretStore`.** They never appear in files, logs, errors, fixtures or MCP results.
5. **Graft edits shared config only inside its marked block** (`~/.gitconfig`), and only after the user has seen the change.
6. **Every stored format has a version.** Unknown newer versions are read-only.
7. **Compose stays in the UI modules** (`:module:core:ui`, `:module:feature:*`, `:module:navigation`, `:app:desktop`); nothing imports Material 3.
8. **Everything is provider-neutral.** Only `:module:data:hosting-github` says GitHub.
9. **Tests never touch the developer's real environment:** `HOME`, git config, SSH and the keychain are sandboxed.

## Cross-cutting

- **Errors:** Sinew's `Result<T>` with `SinewException` codes like `GIT-R-PUSH-REJ`; git's stderr is kept for "Show output".
- **Concurrency:** structured coroutines; processes on `Dispatchers.IO`; everything cancellable, and cancelling kills the process tree.
- **State:** each `RepositorySession` exposes one `StateFlow<RepoSnapshot>`; panes observe it; file events refresh it.
- **Logging:** redacted at write time.

## Where to read more

| Topic | Design doc |
|---|---|
| Layers, modules, storage locations | `docs/design/01 - Foundations/02 - Architecture.md` |
| How git is run and parsed | `docs/design/01 - Foundations/03 - Git Engine.md` |
| Provider abstraction | `docs/design/01 - Foundations/04 - Provider Model.md` |
| UI | `docs/design/01 - Foundations/06 - UI Design.md` |
| Threats | `docs/design/01 - Foundations/07 - Threat Model.md` |
| Terms | `docs/design/01 - Foundations/09 - Glossary.md` |
| Build, tests, CI, libraries, platforms | `docs/design/04 - Development/` |
| Decisions | `docs/design/03 - Project/02 - Decisions.md` |

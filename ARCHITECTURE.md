# Architecture

A one-page map of Graft's code, for contributors and coding agents. The full design is in [`docs/design/`](docs/design/00%20-%20Overview.md). This page says where things are and which rules never bend.

## Bird's-eye view

Graft is a desktop Git client. It shows a repository's whole state (graph, branches, worktrees, shelves, pull requests) and changes it by running the user's own `git` executable. There's no Git library inside. Everything Graft stores is either a Git ref inside the repository (shelves, snapshots) or a small file in the app's folders.

```
 Compose UI (ui-kit, feature-*)      graft CLI / graft mcp (app-cli)
            │                                  │
            ▼                                  ▼
   view models ──► services (core-repo, core-provider) ◄──┘
                         │                │
                         ▼                ▼
                core-git: GitRunner    provider-github ── core-network (Ktor)
                         │
                         ▼
                   git executable
```

## Code map

| Module | What lives there | Must not |
|---|---|---|
| `core-model` | plain data types (`CommitRow`, `Ref`, `Status`, `Shelf`, `Profile`, …), `Result`, `GraftError`, the error-code registry | depend on anything else in Graft |
| `core-git` | `GitRunner`, one class per git command with its parser, process control, `GitSandbox` test fixtures | import Compose; call git any other way |
| `core-repo` | `RepositorySession`: watcher, write queue, snapshot state, op log; graph lane layout; shelves, history, changelists, stacks services | import Compose |
| `core-provider` | the provider SPI (`HostingProvider`, `ChangeRequest`, `Issue`, `Pipeline`) and its contract test kit | mention any concrete provider |
| `core-network` | the HTTP client factory, auth, ETag cache, retry, redacting logs | |
| `core-data` | SQLite cache, settings, profiles storage, `SecretStore`, atomic file writes, format versions | store secrets outside `SecretStore` |
| `provider-github` | the GitHub implementation (GraphQL + REST) | leak GitHub types outside the module |
| `ui-kit` | the Fluent-styled components on Material 3, the theme, `GraftViewModel` | contain feature logic |
| `feature-*` | screens and view models, one area each | import another feature; import Material directly |
| `app-desktop` | windows, menus, wiring, platform integration | |
| `app-cli` | the `graft` command and `graft mcp` (GraalVM native image) | import Compose; change profiles or config; push |

The allowed edges are listed in `config/module-graph.txt`, and `./gradlew check` fails on any other.

## Invariants

These hold everywhere. Breaking one is a bug, even if tests pass.

1. **Git only through `GitRunner`**, with argv lists and machine-readable output (`-z`, porcelain v2). Never a shell, never JGit or libgit2.
2. **One mutating git operation at a time per repository.** Reads run in parallel without optional locks.
3. **Nothing destroys work without a way back:** a snapshot, backup ref or op-log entry comes first. Force pushes are always `--force-with-lease --force-if-includes`.
4. **Secrets only in `SecretStore`.** They never appear in files, logs, errors, fixtures or MCP results.
5. **Graft edits shared config only inside its marked block** (`~/.gitconfig`), and only after the user has seen the change.
6. **Every stored format has a version.** Unknown newer versions are read-only.
7. **Screens use `ui-kit` only.** Only `ui-kit` imports Material.
8. **The core is provider-neutral.** Only `provider-github` says GitHub.
9. **Tests never touch the developer's real environment:** `HOME`, git config, SSH and the keychain are sandboxed.

## Cross-cutting

- **Errors:** `Result<T>` with `GraftError` codes like `GIT-E-PUSH-REJ`; git's stderr is kept for "Show output".
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

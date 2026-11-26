# App shell and UI: no repository calls

**Goal:** no composable and no entry point calls a repository. They render state and send events; view models talk to repositories. Asked for by the author after G2 Task 14.

**Scope:** `Main.kt`, `MainWindow` and `RepositoryScreen` (with `PanelLayoutKeeper`).

**Not in scope:** feature view models that call repositories (the Architecture note allows it: a use case only when there is orchestration); wiring (`AppKoin`) and app services (`GitRepositorySession`).

**Sources:** `01 - Foundations/02 - Architecture.md` (Layers), `03 - Project/02 - Decisions.md` (ADR-23, ADR-24, ADR-28).

## Today

| Where | Repository | Use |
|---|---|---|
| `app/desktop/.../Main.kt` | `SettingsRepository`, `WindowStateRepository`, `RepositoryAccessRepository` | theme and density; restore and save the window; find a folder's repository |
| `module/navigation/.../shell/MainWindow.kt` | `RepositoryAccessRepository`, `CommitRepository` | open a folder; the newest commit that changed a file |
| `module/feature/graph/.../repository/RepositoryScreen.kt`, `PanelLayoutKeeper.kt` | `RepositoryUiStateRepository` | build `RepositoryViewState`; read and save the panel layout |

`PanelLayoutKeeper` also writes past `RepositoryViewState.update`, so the shared `ui` keeps the old layout.

## Design

- All view models are Sinew's `EventActionViewModel`, made by Koin (ADR-28). No new module edge and no new library: `:module:navigation` already has `sinew-viewmodel`, Koin's view-model support, `:module:data:app` and `:module:data:git`.
- **App level** lives in `:module:navigation` (a new `app` package), not in `:app:desktop`, which has no view-model library. `:app:desktop` calls one public composable or holder from there.
- `:module:presentation` stays the home of state that more than one module reads (`WindowState`, `RepositoryViewState`). Theme and density have one reader, so they stay in the app view model's state.

| View model | Lives for | State | Events |
|---|---|---|---|
| `AppViewModel` (navigation) | the app | theme, density, the restored window and missing folders | started, window moved or resized or retabbed |
| `ShellViewModel` (navigation) | one window | the "not a repository" notice, the commit to select | folder opened, file chosen in search |
| `RepositoryViewModel` (graph) | one repository tab | holds the tab's `RepositoryViewState` and the saved panel layout | layout changed |

Closing the app must save before the process ends, so `AppViewModel` also offers `suspend fun saveNow(window)`, which `Main.kt` blocks on, as it does today. It is the one call that isn't an event.

## Tasks

Each is test first, ends with `./gradlew check`, and stops for review.

### Task 1: the repository screen

- `RepositoryViewModel` in `:module:feature:graph`; `RepositoryScreen` takes the shared state from it.
- `PanelLayoutKeeper` takes the `RepositoryViewState`: it calls `load()`, restores `ui.panels`, and saves through `update`.
- **Tests:** a saved layout is restored; a change is saved once after the pause and shows in `ui`; a repository with no saved layout keeps the default.

### Task 2: the window shell

- `ShellViewModel`: opening folders (from Home, drops and `graft open`) and the file-in-HEAD search. `ShellState` keeps navigation only and gets its `locate` from the view model.
- **Tests:** a folder with no repository sets the notice; a file result selects its newest commit; the existing `ShellStateTest` and `ChromeTest` pass unchanged.

### Task 3: the app

- `AppViewModel`: settings changes into theme and density; restore at start (including `GRAFT_REPO`); save after the 500 ms pause; `saveNow`.
- `Main.kt` keeps the process concerns (single instance, the window, macOS title bar) and no repository.
- **Tests:** restore with a missing folder; the debounced save with virtual time; `saveNow` writes at once; `MainWindowTest` and `LocalHistoryWindowTest` pass.

### Task 4: keep it that way

- A test in `:app:desktop` that reads the main sources and fails when a file outside `di` packages, app services and view models asks Koin for a `*Repository`, or a `*Page` or `*Screen` file imports one.
- A ledger line suggesting the author add "UI never calls a repository" to the Architecture note.

**Done when:** the table under "Today" is empty, the guard test passes, and the window behaves as before (tabs restored, theme follows Settings, layout remembered).

## Risks

- **Startup order.** The window's saved bounds are read before the window exists (`runBlocking`). Task 3 keeps that read blocking; making it asynchronous would make the window jump.
- **Saving on close.** A missed `saveNow` loses the last move or tab change. Task 3 tests it.
- **Panel layout timing.** Saving before `load()` would publish defaults over the stored choices. Task 1 loads first and tests it.

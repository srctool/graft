# Draft: ADR-28 (Koin for dependency injection)

Status: **accepted by the author (2026-11-18)**, raised during G1 Task 15, landing in PR #149. It goes into `docs/design/03 - Project/02 - Decisions.md`, and the notes listed at the end change. The notes are the author's to edit.

---

## The ADR (text for `02 - Decisions.md`)

### ADR-28 Koin wires Graft's objects (Accepted 2026-11-18; amends Architecture's "DI" row and the Libraries note's "Not used")

- **Context:**
  - Architecture says "constructor injection, wired by hand in `:app:desktop` (Koin optional)", and Libraries lists "a DI framework (hand wiring)" under *Not used*.
  - By G1 Task 15, the constructor injection holds: view models and use cases take what they need as parameters. The wiring doesn't hold.
    - Each screen's destination composable builds its own use cases (`GitLoadGraphUseCase(commits, ::LaneLayout)` inside `GraphDestination`).
    - The temporary repository screen threads six repositories through a hand-made `RepositoryData`.
    - `:app:desktop` passes data modules down by hand.
  - Each new screen adds to this, and Task 20's main-window host would carry all of it. The author asked for DI.
- **Decision:**
  - **Koin 4.2.2** (`io.insert-koin`, Apache-2.0), through `koin-bom`:
    - `koin-core` in the modules that declare or start wiring;
    - `koin-compose` and `koin-compose-viewmodel` in `:module:feature:*` and `:app:desktop`;
    - `koin-test-junit5` in tests.
    - Task 20 adds `koin-compose-navigation3` with the app navigation.
  - **Constructor injection stays.** Classes take their dependencies as constructor parameters and never call `get()` or `inject()` themselves. Only the `di` packages and `:app:desktop` know Koin. Tests keep building classes directly with fakes.
  - **Data modules:**
    - Each `di` package keeps its plain module class (`GitDataModule(runner)`, `AppDataModule(folders)`, which tests and the CLI use).
    - Each also exposes a Koin `Module` (`gitDataKoin`, `appDataKoin`) that binds every `api` interface. `di` stays the only public package besides `api`.
  - **Features:**
    - Each feature gets a `di` package (Architecture's feature layout gains it) with one Koin `Module` (`graphFeatureKoin`).
    - It declares the feature's use cases as `factory` and its view models as `viewModel`.
    - A destination asks `koinViewModel()` for its view model instead of building it.
  - **Per-tab objects** (a tab's `RepositorySession` and `RepositoryViewState`, and the route's folder) are view-model parameters (`parametersOf`), not Koin scopes, in G1. Task 20 may give each tab a Koin scope when the host owns tabs.
  - **`:app:desktop`** starts Koin once in `main` (`startKoin`), with:
    - an `appKoin` module for the `GitRunner`, `AppFolders`, the write queue and the file watcher;
    - every data and feature module.

    `:app:cli` keeps hand wiring for now: `koin-core` has no reflection and works in a native image, so it can follow later.
  - **Checks:** each module's tests run Koin's `verify()` on its Koin module, so a missing binding fails the build, not the app.
  - No new edge in the module graph: Koin is a library, and each module declares only what it already owns.
- **Consequences:**
  - Destinations shrink to `koinViewModel { parametersOf(...) }`. The repository screen and Task 20's host no longer pass repositories through.
  - Binding errors move from compile time to start time. `verify()` brings them back to test time, but a `get()` with the wrong type compiles.
  - Every data and feature module depends on `koin-core`. The Libraries note gets a row, and its *Not used* line loses "a DI framework".
  - Koin is pinned through its BOM. Upgrades follow its releases, as Sinew's and Camouflage's do.

---

## Changes after acceptance (in PR #149, after Task 15's own work)

1. **The notes** (the author edits, then `scripts/sync-design-docs.py`):
   - Decisions gets ADR-28.
   - Architecture: the "DI" row becomes "constructor injection, wired with Koin (ADR-28)", and the feature package layout gains `di`.
   - Libraries: a Koin row (Apache-2.0; CLI: yes, not used there yet), and "a DI framework" leaves *Not used*.
2. **`gradle/libs.versions.toml`:** `koin-bom` 4.2.2, `koin-core`, `koin-compose`, `koin-compose-viewmodel`, `koin-test-junit5`. NOTICE credits Koin.
3. **`:module:data:git` and `:module:data:app`:** `di/GitDataKoin.kt` and `di/AppDataKoin.kt`, each with a `verify()` test.
4. **`:module:feature:graph`:** `di/GraphFeatureKoin.kt` with the graph, search and compare-summary use cases and the six view models (graph, branches, details, diff, compare). Their destinations use `koinViewModel`, and the repository screen drops `RepositoryData`. A `verify()` test.
5. **`:app:desktop`:** `di/AppKoin.kt` and `startKoin` in `main`. The measurement test starts Koin with the same modules and a sandboxed runner.
6. **Ledger:** the ADR's acceptance, the changes, and a ruling on anything that differs from the above.

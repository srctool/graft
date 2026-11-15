# Draft: ADR-26 (G1's modules, Navigation 3, and how features get their dependencies) and the changes it needs

Status: **accepted (2026-11-15) and written into the notes** as ADR-26, from the G1 plan's decisions D1, D2, D3 and D9 (`plans/G1.md`). The accepted wording is in `docs/design/03 - Project/02 - Decisions.md`; the module graph, Libraries and build changes below are applied (G1 Task 0).

---

## The ADR (text for `02 - Decisions.md`)

### ADR-26 G1's modules, Navigation 3, and dependencies passed through navigation (Accepted 2026-11-15; extends ADR-24 and ADR-25)

- **Context:**
  - G1 (Read-only browser) has screens with no module in ADR-24's graph: Home, workspaces, Clone, Init and Onboarding aren't about one repository, and Settings, Search Everywhere, About and the left panel collect contributions from many features over the milestones.
  - The Libraries note lists no navigation library. The author's Kotlin guide uses Navigation 3; JetBrains publishes it for Compose Multiplatform with a desktop artifact (`org.jetbrains.androidx.navigation3:navigation3-ui` 1.2.0, Apache-2.0), and `lifecycle-viewmodel-navigation3` 2.11.0 gives each entry its own `ViewModelStore`.
  - `:app:desktop` builds the data modules (hand-wired DI), but `:module:navigation` can't name their types: the G0 spike needed a temporary `:app:desktop -> :module:feature:graph` edge to reach a screen.
  - Platform Integration defines a `FileWatcher` but no module holds it. Only the app's repository session uses it, and it's platform code (FSEvents through `directory-watcher` and JNA, inotify limits).
- **Decision:**
  - **`:module:feature:home`** (new) holds what isn't about one open repository: Home, workspaces, Clone, Init, Onboarding. **`:module:feature:graph`** holds everything inside one open repository: the graph, its left-panel sections, commit details, the diff screen, Compare and the reflog timeline. Features still never import each other.
  - **Hosts in `:module:navigation`:** the main-window shell, the left panel, Settings, Search Everywhere and About. Features contribute to them (left-panel sections, settings pages, search results, actions) through small contracts in `:module:core:ui`, which both sides already depend on, so a later milestone adds a section without touching the host.
  - **Navigation 3:** `@Serializable` keys, a `{Screen}Navigation.kt` per screen (its key and the destination composable that builds the view model and connects it to the page), a `{Feature}Navigation.kt` per feature with its `entry<Key>` registrations, and the app navigation (back stacks per tab, `NavDisplay`, the hosts) in `:module:navigation`. One `ViewModelStore` per entry through `lifecycle-viewmodel-navigation3`.
  - **Dependencies through navigation:** `:app:desktop` is the bootstrap. It creates the `GitRunner`, the data modules and the app services, and hands them to the app navigation, which passes each feature's destinations the `api` types they need. New edges **`:module:navigation -> :module:data:git`, `:module:data:app`, `:module:data:hosting`**; the data modules' `di` and `implementation` stay out of reach (only `api` and `di` are public, and navigation receives built modules). `:module:data:hosting-github` stays wired by `:app:desktop` alone (ADR-4).
  - **`:module:core:watcher`** (new, no Compose) holds `FileWatcher` and its `directory-watcher` implementation. Only `:app:desktop` depends on it.
- **Consequences:**
  - The spike's temporary edge goes away in G1 (Task 20): the app reaches every screen through navigation.
  - Graft's navigation matches the Kotlin guide's three levels, so the guide's rules (keys carry only IDs, view models never navigate, one store per entry) apply as written.
  - Navigation 3 and its view-model integration are new dependencies, pinned with Compose and Lifecycle; the license check covers them.
  - Navigation sees data `api` types, so a change to a data module's `api` can now break navigation's compile, not only features'.

---

## Module graph changes (`config/module-graph.txt`)

```
# feature:home (new), the same edges as feature:graph
:module:feature:home -> :module:presentation
:module:feature:home -> :module:data:git
:module:feature:home -> :module:data:app
:module:feature:home -> :module:data:hosting
:module:feature:home -> :module:core:ui
:module:feature:home -> :module:core:exception
:module:feature:home -> :module:domain

# navigation: the new feature, and the data modules it passes on
:module:navigation -> :module:feature:home
:module:navigation -> :module:data:git
:module:navigation -> :module:data:app
:module:navigation -> :module:data:hosting

# core:watcher (new)
:module:core:watcher -> :module:core:exception
:app:desktop -> :module:core:watcher
```

Removed in G1 Task 20, not now: `:app:desktop -> :module:feature:graph` (the spike's).

## Library changes (`04 - Development/07 - Libraries.md`, `gradle/libs.versions.toml`)

| Need | Choice | License | CLI | Notes |
|---|---|---|---|---|
| Navigation | `org.jetbrains.androidx.navigation3:navigation3-ui` 1.2.0 + `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` 2.11.0 | Apache-2.0 | n/a | Compose modules only; keys are `@Serializable` (`kotlinx-serialization`, already listed); Lifecycle 2.10 from Navigation 3 resolves to 2.11, which `sinew-viewmodel` already uses |

`io.methvin:directory-watcher` is already listed (File watching); it's added to the catalog in G1 Task 2.

## Note edits after acceptance

| Note | Change |
|---|---|
| `03 - Project/02 - Decisions.md` | ADR-26 as above, marked Accepted with the date |
| `01 - Foundations/02 - Architecture.md` | the module diagram and table: `:module:feature:home`, `:module:core:watcher`, navigation's new role (app navigation on Navigation 3, the hosts, passing data `api` types); the rule "navigation imports features, never the reverse" gains "and the data modules' `api`, to pass them on" |
| `04 - Development/07 - Libraries.md` | the Navigation row above; "a DI framework" stays in **Not used** (hand wiring, now through navigation) |
| `04 - Development/08 - Platform Integration.md` | 3.1 and the build steps name `:module:core:watcher` as the `FileWatcher`'s home |
| `05 - Design/01 - Screen Inventory.md` | nothing (screens unchanged); the module per screen is in `plans/G1.md` |

## Build changes (G1 Task 0, after acceptance)

- `settings.gradle.kts`: `:module:feature:home`, `:module:core:watcher`.
- `module/feature/home/build.gradle.kts` (`graft.compose-library`, dependencies as the edges above), `module/core/watcher/build.gradle.kts` (`graft.kotlin-library`), each with a placeholder file.
- `module/navigation/build.gradle.kts`: `:module:feature:home`, the three data modules, Navigation 3 and its view-model integration.
- `gradle/libs.versions.toml`: `navigation3` and `lifecycle-viewmodel-navigation3`.
- `build-logic` rule tests: `:module:core:watcher` isn't a UI module (Compose and Camouflage rejected there).
- `./gradlew check` green.

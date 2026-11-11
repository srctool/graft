# Draft: ADR-23 (Sinew's seams) and the changes it needs

Status: **accepted (2026-11-11) and written into the notes** as ADR-23 (Sinew seams) and ADR-24 (module structure, build order). The accepted wording is in `docs/design/03 - Project/02 - Decisions.md`.

---

## What Sinew 1.0 offers on the JVM

All on Maven Central as `com.srctool.sinew:sinew-*:1.0.0`, Apache-2.0, built on Kotlin 2.4.21, coroutines 1.11.0 and Compose 1.12.1, the versions Graft uses.

| Package | JVM dependencies | Compose? | Fits Graft's |
|---|---|---|---|
| `sinew-models` | kotlinx-serialization-core | no | `Result` (`Success` / `Failure`), `ErrorMessage`, `ExceptionLayer` |
| `sinew-exception` | `sinew-models`, coroutines | no | `SinewException`, the built-in types, `processCall` / `processResult` guards, `CrashReporter`, `Localizer` |
| `sinew-l10n` | Compose resources | **yes** | English and Indonesian texts for the built-in error keys |
| `sinew-presentation` | coroutines | no | `EventActionHandler` (Event → Action → State + Effect), `EffectEmitter` |
| `sinew-viewmodel` | `lifecycle-viewmodel-desktop` 2.11, Compose runtime | **yes** | ViewModel bases on AndroidX ViewModel, `ObserveEffects` |
| `sinew-network` | Ktor 3.6 (OkHttp engine at runtime) | no | `SinewHttp.client` (the engine can be passed explicitly), auth with single-flight refresh, timeout and redacting-log layers, `processApiCall`, retry, polling, `NetworkChecker` |
| `sinew-paging` | `sinew-models`, `sinew-exception` | no | `Pager`, `PagingState` (PR, issue and notification lists in G6) |
| `sinew-camouflage` | Camouflage | yes | `PagingState.toCamo()` → `CamoPagedListState` |
| `sinew-storage`, `sinew-security` | DataStore, Tink | no | not a fit: Graft's secrets live only in the OS keychain (ADR-7) |
| `sinew-devtools(-ui)` | Material toolkit | yes | not a fit: a mobile overlay on Material, which Graft doesn't ship (ADR-22) |

How Graft's ADR-9 seams line up:

| Seam (ADR-9) | Graft today | Sinew |
|---|---|---|
| Errors | `GraftError` (sealed, not a `Throwable`), `Result.Ok` / `Result.Err`, codes `GIT-E-PUSH-REJ` | `SinewException` (an `Exception`), `Result.Success` / `Result.Failure`, codes `ORD-R-GO-NIC` |
| Layers in the code | `E` engine, `S` service, `V` view model, `P` provider | Kotlin: `VM`, `UC`, `R`, `UT` (the base notes also name `SV` app service, which the Kotlin enum lacks) |
| Function part | 2–8 letters (`PUSH`, `CLONE`, `RPARSE`) | 2–4 letters (`GO`, `CO`) |
| Messages | an English `message` | `ErrorMessage.Local(key, args, fallback)` or `Server(text)`, rendered by a `Localizer` |
| View model | `GraftViewModel<State, Event, Effect>` on AndroidX ViewModel (planned) | `EventActionHandler` + `sinew-viewmodel` on AndroidX ViewModel |
| Network | `core-network` on Ktor with the Java engine (planned) | `sinew-network` on Ktor; the engine is a parameter |

---

## The ADR (text for `02 - Decisions.md`)

### ADR-23 Adopt Sinew's error model, presentation and network seams now (Proposed 2026-11-11; completes ADR-9's seams)

- **Context:**
  - ADR-9 kept four seams so Graft could switch to Sinew and Camouflage later. ADR-22 switched `ui-kit` to Camouflage. Sinew 1.0 is now published, with JVM builds that fit Graft's versions.
  - G0 Task 5 is writing `GraftError` and `Result` right now. Switching before that code is committed costs nothing; switching after G1 means touching every service and view model.
  - `GraftError`'s code format was chosen to map one to one onto `SinewException`, and `GraftViewModel` was designed after Sinew's Event → Action → State + Effect.
- **Decision:**
  - **Errors:** Graft's failures are `SinewException` subclasses and every fallible call returns Sinew's `Result`. `GraftError` and Graft's own `Result` go away. `core-model` depends on `sinew-models` and `sinew-exception` (pure Kotlin, no Compose, native-image friendly).
  - **Layers:** Graft's layers map onto Sinew's: the git engine and providers are data sources (`R`), services are use cases (`UC`), view models are `VM`, retry and polling helpers are `UT`. Codes become `GIT-R-PUSH-REJ`.
  - **Function codes** follow Sinew's 2–4 letters (`PUSH`, `CLON`, `SHLV`). Module codes stay (`GIT`, `REPO`, `PRV`, `NET`, `DATA`, `UI`, `CLI`, `SSH`, `PRF`).
  - **Types:** HTTP failures use Sinew's built-in types through `processApiCall` (`NIC`, `RTO`, `UNA`, `FOR`, `NTF`, `CFL`, `RTL`, `SRV`, …). Git failures are Graft's own `SinewException` subclasses with git's argv, exit code and stderr (redacted) and Graft's type codes: `REJ` rejected, `AUTH`, `CONF` conflict, `LOCK`, `NF` not found, `NET`, `TO` timeout, `VER` version, `EXIT` unrecognized non-zero exit. Unreadable git output is Sinew's `ParseException` (`DF`) and anything unmatched is `UnexpectedException` (`GEN`); both are reported to the `CrashReporter`. Cancellation is never a failure: `CancellationException` is rethrown, so `CANC` is dropped.
  - **Registry:** `core-model`'s `ErrorCodes.kt` stays the registry of Graft's modules, functions and types, with the duplicate test.
  - **Messages:** every Graft exception carries an `ErrorMessage.Local` key with an English fallback from day one. The UI renders it with a `Localizer`; `sinew-l10n` supplies English and Indonesian for the built-in keys, used only from `ui-kit` (it pulls in Compose). This is G8's i18n groundwork.
  - **Crash reports:** G7's opt-in crash reporting implements Sinew's `CrashReporter`; until then it's the no-op default. Reports stay previewed and redacted ([[projects/graft/03 - Project/07 - Privacy|Privacy]]).
  - **Presentation:** `GraftViewModel` is Sinew's ViewModel base from `sinew-viewmodel` (on AndroidX ViewModel), wrapped in `ui-kit` (G1).
  - **Network:** `core-network` wraps `sinew-network`: `SinewHttp.client` with the **Ktor Java engine** passed explicitly (OkHttp excluded), Sinew's auth layer with a `TokenSource` backed by `SecretStore`, its redacting log, retry and `processApiCall`. Graft adds the ETag cache and the rate-limit status on top (G6).
  - **Paging:** `sinew-paging` and `sinew-camouflage` are allowed for G6's PR, issue and notification lists; added when those tasks need them.
  - **Not used:** `sinew-storage` and `sinew-security` (secrets only in the OS keychain, ADR-7; settings and the cache stay in `core-data`), `sinew-devtools` (a Material-based mobile overlay).
- **Consequences:**
  - The error invariant reads "every failure is a `SinewException` with a `MODULE-LAYER-FUNCTION-TYPE` code; git failures keep stderr and exit code".
  - `core-model` gains external dependencies (Sinew, kotlinx-serialization-core), still nothing from Graft.
  - A Sinew API change touches Graft; the version is pinned, and Graft's needs feed back into Sinew as its first desktop app.
  - Task 5's paused code is rewritten onto Sinew's types before it's committed.

---

## Open questions

| # | Question | Decision |
|---|---|---|
| Q1 | **Layer mapping** (see Module structure below): engine and providers → `R`, services → `UC`, view models → `VM`, retry/polling → `UT`? | **Decided (2026-11-11):** sources and repositories `R`, use cases `UC`, ViewModels `VM`, helpers `UT`, app services `SV` (requested in sinew-kotlin#96 and sinew-dart#91) |
| Q2 | **Git type codes:** keep Graft's (`REJ`, `AUTH`, `CONF`, `LOCK`, `NF`, `NET`, `TO`, `VER`, `EXIT`), or reuse Sinew's where the meaning overlaps (`NET` → `NIC`, `TO` → `RTO`, `AUTH` → `UNA`)? | **Decided (2026-11-11): keep Graft's types for git**; HTTP calls use Sinew's |
| Q3 | **Function codes:** Sinew's 2–4 letters, or keep up to 8? | **Decided (2026-11-11): 2–4 letters**, as Sinew |
| Q4 | **HTTP engine:** pass the Ktor Java engine and exclude OkHttp, or use Sinew's default OkHttp? | **Decided (2026-11-11): Ktor Java engine**, OkHttp excluded |
| Q5 | **When each seam switches:** ADR-9 said `core-network` first and `ui-kit` last, one PR each, between milestones | **Decided (2026-11-11):** errors now (Task 5), `GraftViewModel` in G1, `core-network` in G6, each in its own commit |
| Q6 | **`sinew-l10n`:** use it for the built-in keys from G1, or only from G8's i18n pass? | **Decided (2026-11-11): from G1** in `ui-kit` |

---

## ADR-24 (separate ADR): module structure (decided 2026-11-11)

Graft follows the author's architecture board (FigJam "Brainstorming", the *Modular App with Navigation* shape), which Sinew's App Architecture came from. It replaces today's `core-*` / `provider-github` / `ui-kit` split.

| Module | Holds | Depends on | Compose |
|---|---|---|---|
| `:app:desktop` | startup, hand-wired DI, the root `CamouflageProvider`, window shell | everything, for wiring | yes |
| `:app:cli` | the `graft` CLI and `graft mcp` (native image) | `:data:*`, `:core:*` except `:core:ui` | **no** |
| `:navigation` | routes for the center pane (graph, diff, PR, issue, compare, bisect) and tabs; features never import each other's screens | `:feature:*`, `:presentation`, `:core:ui` | yes |
| `:feature:*` | one area each: screens **and** their ViewModels (`.presentation` package), plus the area's own use cases (the board's optional domain layer) | `:presentation`, `:data:*`, `:core:*` | yes |
| `:presentation` | **app-level state**, split by case: `WindowState` (tabs, active repository, its profile), `OperationsState` (background work for the status bar), `InboxState` (unread notifications), `ProviderState` (sign-in, rate limit) | `:data:*`, `:core:models` | no (state holders only) |
| `:data:git` | git-backed repositories (commits, refs, working copy, stash, shelves, local history, changelists, worktrees, submodules, sync); its source is `:core:git` | `:core:git`, `:core:models`, `:core:exception` | no |
| `:data:app` | profiles, settings, repositories and workspaces | `:core:database`, `:core:preference`, `:core:security`, `:core:models` | no |
| `:data:hosting` | provider-neutral repository interfaces and models (`ChangeRequest`, `Issue`, `Pipeline`, `Account`, `Notification`) and the contract test kit (ADR-4) | `:core:models`, `:core:exception` | no |
| `:data:hosting-github` | the GitHub implementation and its API source; the only module that says "GitHub" | `:data:hosting`, `:core:network`, `:core:database` | no |
| `:core:ui` | the theme (Carbon skin, Atom One colors, IBM Plex Sans, 24/32 px density), Graft-only shared components (diff viewer, ref chips, avatars), stopgap components until Camouflage ships them, the `Localizer` with `sinew-l10n` | Camouflage, `sinew-l10n`, `sinew-viewmodel` | yes |
| `:core:git` | `GitRunner`: the configured git process runner, write queue, environment | `:core:models`, `:core:exception` | no |
| `:core:network` | the configured HTTP client on `sinew-network` (Ktor Java engine), token source, ETag cache | `sinew-network`, `:core:security` | no |
| `:core:database` | SQLDelight cache | — | no |
| `:core:security` | `SecretStore` on the OS keychain | — | no |
| `:core:preference` | versioned settings files, atomic writes | — | no |
| `:core:models` | domain models shared by the data modules (commit, ref, status, shelf, profile, …) | `sinew-models` | no |
| `:core:exception` | Graft's `SinewException` subclasses (git failures with argv, exit code, stderr), the error-code registry, redaction | `sinew-exception` | no |
| `:core:notification` | desktop notifications | — | no |
| `:core:configuration` | app folders and platform paths | — | no |

Not used from the board's `:core` list: `:analytics` (Graft collects nothing, Privacy note); `:static`, `:extension`, `:utils` until something needs them.

**Layers in codes:** sources and repositories `R`, use cases `UC` (inside features, or a shared domain module once a flow is shared), ViewModels `VM`, Sinew's retry and polling helpers `UT`, app services `SV` once Sinew has it (srctool/sinew-kotlin#96, srctool/sinew-dart#91; `UC` until then).

**Compose rule:** only `:core:ui`, `:feature:*`, `:navigation` and `:app:desktop` may use Compose; `:app:cli` never depends on them.

**Decided (2026-11-11):**
- Shared use cases: a `:domain` module, created only when a flow is first needed by two features; until then use cases live in their feature.
- App services (session and watcher, background fetch, auto-snapshot, inbox poller) live in `:app:desktop`, the only app with an app lifetime.
- The module restructure is its own **ADR-24**; ADR-23 covers only the Sinew seams.
- **Build order: bottom to top.** Work goes `:core:*` → `:data:*` → use cases → `:presentation` → `:feature:*` UI → `:navigation` / `:app`, so the author reviews the data layer before any UI is built on it, and the best practices found in review go into the notes before the next layer starts.

## Changes to the design notes

| Note | Section | Change |
|---|---|---|
| `03 - Project/02 - Decisions.md` | ADR-9 heading | add "(seams adopted by ADR-22 and ADR-23)" |
| | new | ADR-23, text above |
| `01 - Foundations/02 - Architecture.md` | Stack table: Presentation, Errors, Network rows | → `GraftViewModel` on Sinew's ViewModel base (`sinew-viewmodel`, AndroidX ViewModel); Sinew's `Result` + `SinewException` with Graft's codes; `core-network` on `sinew-network` with the Ktor Java engine, plus Graft's ETag cache |
| | Error model | rewrite: Graft's failures are `SinewException` subclasses, example `GIT-R-PUSH-REJ`; git exceptions keep argv, exit code and stderr; messages are `ErrorMessage.Local` keys |
| `04 - Development/02 - Conventions.md` | §3 Error code registry | layer values → `R` data source (git engine, providers), `UC` service, `VM` view model, `UT` utility; function → 2–4 letters; types → Graft's git types plus Sinew's built-ins for HTTP, `DF` parse, `GEN` unexpected; `CANC` removed (cancellation is rethrown); example `GIT-R-PUSH-REJ` |
| `05 - Design/03 - UX Writing.md` | Errors example | `GIT-E-PUSH-REJ` → `GIT-R-PUSH-REJ` |
| `04 - Development/07 - Libraries.md` | runtime table | add Sinew rows: `sinew-models`, `sinew-exception` (core), `sinew-presentation`, `sinew-viewmodel`, `sinew-l10n` (UI), `sinew-network` (`core-network`), `sinew-paging`, `sinew-camouflage` (G6); Apache-2.0; native image: yes for models, exception, network, paging |
| | HTTP row | Ktor with the Java engine, **through `sinew-network`**; OkHttp excluded |
| | Not used | "Sinew, until ADR-9's adoption point" → `sinew-storage`, `sinew-security`, `sinew-devtools` (ADR-23) |
| `00 - Overview.md` | stack line | "Ktor and AndroidX ViewModel first" → "Sinew for errors, view models and the network client (ADR-23)" |
| | Open questions | tick the Sinew item with ADR-23 |
| `01 - Foundations/03 - Git Engine.md` | Runner | note that `Result` is Sinew's and failures are git `SinewException`s |

## Changes in this repository (after the notes are synced)

| File | Change |
|---|---|
| `AGENTS.md` §2 | the error row → "Errors are `SinewException`s with a `MODULE-LAYER-FUNCTION-TYPE` code; every fallible call returns Sinew's `Result`; git failures keep stderr and exit code" (Architecture, ADR-23); the Sinew row → "Camouflage is the `ui-kit` toolkit (ADR-22); Sinew provides errors, view models and the network client (ADR-23)" |
| `ARCHITECTURE.md` | `core-model` row → "plain data types, Graft's `SinewException` subclasses, the error-code registry"; `core-network` row → "Graft's client on `sinew-network`: Java engine, token source, ETag cache"; Errors line → `GIT-R-PUSH-REJ` |
| `gradle/libs.versions.toml` | `sinew = "1.0.0"`, `sinew-bom`, `sinew-models`, `sinew-exception` now; the others with the tasks that use them |
| `plans/G0.md` Task 5 | "`GraftError` sealed type … `GIT-E-<FN>-<TYPE>`" → "Graft's git `SinewException` subclasses with `GIT-R-<FN>-<TYPE>` codes; `GitRunner` returns Sinew's `Result`" |
| `plans/progress.md` | ADR-23 line and rulings |
| Task 5's uncommitted code | `core-model`: drop `GraftError`, `Result`, `GraftException`; keep `ErrorCodes` (registry of modules, functions, types) and `Redaction`; add `GitException` and its subclasses. `core-git`: return Sinew's `Result`; map failures to the subclasses; parse failures → `ParseException`; tests follow |
| GitHub issue #6 (Task 5) | scope and acceptance in Sinew's terms |
| GitHub issue #11 (G1 `ui-kit`) | add `GraftViewModel` on `sinew-viewmodel` and the `Localizer` with `sinew-l10n` |
| GitHub issue #73 (G6 `core-network`) | `sinew-network` with the Java engine, Graft's ETag cache on top |

## Order of work, once accepted

1. You answer Q1–Q6; ADR-23 and the note changes go into the notes; `docs/design/` is synced.
2. The repo files above change in one commit (`docs: adopt Sinew's seams (ADR-23)`).
3. Task 5 resumes on Sinew's types.

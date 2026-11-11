---
tags: [graft, project, adr]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 02 - Decisions

ADRs are never edited after acceptance; a later ADR supersedes. Status: **Proposed** until the G0 spike, then Accepted.

### ADR-1 Kotlin + Compose Multiplatform Desktop (Accepted 2026-10-18)
- **Context:** the app needs a fast virtualized graph, native-feeling windows on 3 OSes, and a stack the author already knows (Kotlin, Compose).
- **Options:**

  | Option | For | Against |
  |---|---|---|
  | **Compose Desktop (JVM)** | author's stack; can adopt Camouflage and Sinew later; Canvas is fast enough for the graph; one language | JVM memory (~200 MB base), bigger installer (~80 MB with jlink), smaller desktop ecosystem |
  | Tauri (Rust + web UI) | small binary, low memory; GitButler proves it | two languages; web graph rendering needs care |
  | Electron | biggest ecosystem; GitKraken, GitHub Desktop | memory use, the thing users complain about |
  | Flutter desktop | author knows it | desktop text/menus/multi-window still weaker |
- **Decision:** Compose Desktop. The G0 spike must still render a 500k-commit graph at 60 fps within 400 MB; if it fails, this ADR is revisited before G1.
- **Consequences:** jlink/jpackage packaging. Libraries are plain Compose/Ktor/AndroidX until ADR-9 says otherwise.

### ADR-2 Drive the git CLI, don't embed a Git library
- **Context:** libgit2 and JGit lag behind Git on worktrees, hooks, signing, LFS, credential helpers and config semantics.
- **Decision:** run the installed `git` (>= 2.38) with machine-readable output ([Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md)).
- **Consequences:** process overhead mitigated by batching and caching; Graft can always show the exact command.

### ADR-3 One mutation at a time per repository
- **Context:** Git serializes on `index.lock`; parallel writes fail randomly.
- **Decision:** a write queue per repository session; reads run in parallel with `GIT_OPTIONAL_LOCKS=0`.

### ADR-4 Provider-neutral core, GitHub as the first provider
- **Context:** GitHub first, others later; GitKraken-style "works with everything" is a goal.
- **Decision:** `core-provider` SPI with neutral types (`ChangeRequest`, `Review`, `Check`, `Issue`) and capability flags. Only `provider-github` mentions GitHub ([Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md)).
- **Consequences:** a little mapping overhead; a contract test kit every provider must pass.

### ADR-5 Shelves are commits under `refs/graft/shelves/`
- **Context:** JetBrains stores shelves as patch files in `.idea/shelf`. Patches lose exactness for binaries and can't three-way merge; files in `.idea` are per project, not per repo.
- **Decision:** each shelf is a commit (parent = base) referenced by `refs/graft/shelves/<ulid>`, with metadata in trailers. Export to `.patch` for interop ([Shelves](../02%20-%20Features/06%20-%20Shelves.md)).
- **Consequences:** shelves are shared across worktrees, gc-safe, visible to the git CLI, and not pushed by default.

### ADR-6 Safe destructive operations
- **Decision:** `--force-with-lease --force-if-includes` for force push; destructive actions list what is lost; the op log stores refs before/after for undo; discard keeps a backup shelf.

### ADR-7 Tokens only in the OS keychain; no telemetry by default
- **Decision:** no secret in files or logs; crash reporting is opt-in and redacts paths and remote URLs.

### ADR-8 OAuth device flow for GitHub
- **Context:** an open-source app can't keep a client secret.
- **Decision:** device flow (client id only) by default; PAT and `gh auth token` as alternatives.

### ADR-9 Build without Sinew and Camouflage first; adopt them later behind seams (seams adopted by ADR-22 and ADR-23)
- **Context:** Sinew and Camouflage are still being designed. Making Graft wait for them, or build on moving APIs, would block Graft and push Graft's needs into their design too early.
- **Decision:** Graft 1.0 uses plain libraries: Compose Material 3, AndroidX ViewModel, Ktor, its own `Result`/`GraftError`. Four seams make the later switch mechanical:

  | Seam | Now | Later |
  |---|---|---|
  | `ui-kit` module: every screen uses `GraftButton`, `GraftTextField`, `GraftList`… never Material directly (CI lint) | wraps Material 3 | wraps Camouflage (desktop skins) |
  | `GraftViewModel<State, Event, Effect>` with `mapEventToAction` / `onAction` / `setState` / effects | on AndroidX ViewModel | on Sinew presentation + adapter |
  | `GraftError` codes in Sinew's `module-layer-function-type` format | own sealed class | `SinewException` |
  | `core-network` API (client factory, auth layer, ETag, retry) | Ktor directly | `sinew-network` |
- **Adopt when:** Sinew 1.0 and Camouflage with a desktop skin are published, and Graft is between milestones (not mid-milestone). Each seam switches in its own PR, `core-network` first, `ui-kit` last.
- **Consequences:** a little wrapper code now. Graft becomes the first real desktop app to test Sinew and Camouflage on, and its pain points feed back into them.

### ADR-10 License: GPL-3.0-or-later
- **Context:** Graft is open source. The author doesn't want closed copies of it, and the Mac App Store isn't a goal. Apache-2.0 and GPL-3.0 were compared in [Open Source](03%20-%20Open%20Source.md).
- **Decision:** GPL-3.0-or-later, contributions under the DCO (no CLA), the name protected by `TRADEMARKS.md`.
- **Consequences:** forks stay open; no App Store; in-process plugins and every dependency must be GPL-3.0-compatible (checked in CI); the license can't realistically change once there are outside contributors.

### ADR-11 Profiles apply identity at run time; Graft never silently rewrites git config (Superseded by ADR-13)
- **Context:** people commit as different identities (Personal, Work) and GitKraken-style profiles are wanted. Writing `user.email` into configs behind the user's back breaks their terminal setup.
- **Decision:** a profile holds identity, provider accounts, SSH mode and workspace. Graft passes the identity on its own commands (`-c`, `GIT_SSH_COMMAND`). It writes to repo config or `~/.gitconfig` (`includeIf`) only when the user asks, showing the change first ([Profiles](../02%20-%20Features/11%20-%20Profiles.md)).
- **Consequences:** commits from the terminal may use a different identity than Graft's unless the user chooses "make the terminal match"; Graft warns about mismatches before commit and push.

### ADR-12 SSH through the system OpenSSH; one optional managed key per profile
- **Context:** SSH behavior (config, agent, hardware keys, ProxyJump, known_hosts) must match the terminal, the same reason as ADR-2.
- **Decision:** use `ssh`, `ssh-keygen` and `ssh-agent` from the system (or the one `git` uses on Windows). A profile either uses system SSH untouched, or a managed key passed with `-i … -o IdentitiesOnly=yes`. Host keys are verified against provider-published fingerprints; never `StrictHostKeyChecking=no` ([SSH](../02%20-%20Features/12%20-%20SSH.md)).
- **Consequences:** no SSH library to maintain; several accounts on one host work without host aliases.

### ADR-13 The repository's profile lives in git config, for every tool (supersedes ADR-11)
- **Context:** ADR-11 applied the profile only to Graft's own commands. Commits and PRs are now often made by AI agents (Claude Code, Cursor) and the terminal. Those used the global identity, and GitKraken's global "active profile" made Cursor commit as whichever profile was active. Claude Code got stuck on SSH prompts, and `gh` opened PRs from its single active account.
- **Decision:**
  - The repository decides its profile; there is no global active profile for identity.
  - Graft writes each profile to a git config file it owns, with identity, signing, `core.sshCommand` and a credential helper.
  - It maps the profile's rules to `includeIf` (`gitdir:`, `hasconfig:remote.*.url:`) in one marked block of `~/.gitconfig`, and pinned repos get an `include` in `.git/config`.
  - A `gh` shim gives each profile its own `GH_CONFIG_DIR`.
  - The SSH wizard makes non-interactive use work.
  - `graft whoami` / `graft doctor` show what any tool will use ([Terminal and AI Agents](../02%20-%20Features/13%20-%20Terminal%20and%20AI%20Agents.md)).
- **Consequences:** Graft does edit `~/.gitconfig`, but only inside its block, after showing the diff once. If the user refuses, it falls back to ADR-11 behavior with a warning banner. Graft's own commands no longer need `-c` overrides.

### ADR-14 Local history as snapshot commits under `refs/graft/history/`
- **Context:** uncommitted work, often edited by AI agents, can be lost with no Git record. JetBrains' Local History solves this inside the IDE only.
- **Decision:** snapshots are commits of the whole working tree (tracked plus untracked, not ignored), built with a temporary index, chained per worktree under `refs/graft/history/<worktree-id>`. They're deduplicated by tree, thinned by age, budgeted to 1 GB, and taken on changes, before risky operations, and from an optional agent hook through `graft snapshot` ([Local History](../02%20-%20Features/14%20-%20Local%20History.md)).
- **Consequences:** reuses the shelf machinery; costs disk only for changed content; never pushed.

### ADR-15 Changelists are a per-repo mode, exclusive with the staging area
- **Context:** a hunk that is both "staged" and "in changelist B" has no clear meaning, which is why JetBrains makes the two exclusive too.
- **Decision:** changelists replace Staged/Unstaged when turned on. Assignments by file or hunk are stored per worktree in `$GIT_DIR/graft/changelists.json`. Committing a list uses a temporary index ([Changelists](../02%20-%20Features/15%20-%20Changelists.md)).
- **Consequences:** terminal users of the same repo see a normal index; switching modes moves staged changes into a "Staged" list.

### ADR-16 Stack parents in git config; restack with `rebase --update-refs`
- **Decision:** `branch.<name>.graftParent` and `graftParentTip` in repo config; restacking uses Git's own `--update-refs` (≥ 2.38), and `--onto <graftParentTip>` after squash merges ([Stacked Branches](../02%20-%20Features/25%20-%20Stacked%20Branches.md)).
- **Consequences:** no hidden database; any tool can read the stack; works from the CLI too.

### ADR-17 Commit guards are local, and also check commits Graft didn't make
- **Decision:** secret, sensitive-file, size and conflict-marker checks run locally with bundled rules, on Graft commits and on every commit in a push range. `graft guard` and opt-in hooks extend them to other tools. Graft never replaces an existing hook ([Commit Guards](../02%20-%20Features/19%20-%20Commit%20Guards.md)).
- **Consequences:** agents' commits are caught before Graft pushes them, though not if an agent pushes by itself without the hook.

### ADR-18 History rewriting through git-filter-repo, always with a ref backup
- **Decision:** use `git filter-repo` (offer to install it). Before any rewrite, copy all refs to `refs/graft/backup/<timestamp>/` and take a Local History snapshot. For leaked secrets, lead with "rotate the secret" ([History Rewriting](../02%20-%20Features/30%20-%20History%20Rewriting.md)).

### ADR-19 One `graft` binary for CLI and MCP: native image, local only
- **Context:** agents and hooks call `graft` often (snapshots before every edit), so a JVM's startup time would hurt. MCP clients start servers as local child processes.
- **Decision:** `app-cli` shares the `core-*` modules and is compiled with GraalVM native-image. It works without the app and notifies a running app over a local socket. `graft mcp` is a **stdio-only** MCP server: no network listener, no remote deployment. The CLI and MCP never change profiles or config, never reveal tokens, and never push, force or rewrite history ([CLI](../02%20-%20Features/31%20-%20CLI.md), [MCP Server](../02%20-%20Features/32%20-%20MCP%20Server.md)).
- **Consequences:** two build outputs (app, CLI) from one codebase; native-image needs reflection config for the parsers and SQLDelight; MCP tools are thin wrappers over CLI commands, so both stay in sync.

### ADR-20 GitKraken layout, JetBrains style, on compact Material 3 (visual style superseded by ADR-21; Material 3 base superseded by ADR-22)
- **Context:** the author likes GitKraken's layout and JetBrains' look and behavior. Material 3's defaults are built for touch: big controls, pill buttons, ripples, tinted surfaces.
- **Options:**

  | Option | For | Against |
  |---|---|---|
  | **Material 3, restyled compact** | widely used, stable; already the `ui-kit` base (ADR-9); direct path to Camouflage's Material skin | trees, tables, split panes and tool windows must be built; text fields need custom decoration to get small |
  | JetBrains Jewel | IntelliJ look out of the box, trees and popups included | API still changing; tied to IntelliJ Platform and Compose versions; no Camouflage path |
  | Fully custom on `compose.foundation` | total control | everything to build, including accessibility |
- **Decision:**
  - **Layout:** GitKraken's.
  - **Behavior:** JetBrains' (tool windows, Search Everywhere, speed search, diff and merge viewers, keymap).
  - **Toolkit:** Material 3 with a Graft theme that makes it compact and flat (28 px controls, 4 px corners, no ripple, no tonal elevation, 13 px Inter), inside `ui-kit` ([UI Design](../01%20-%20Foundations/06%20-%20UI%20Design.md)).
  - **Title bar:** in-title-bar toolbar on macOS only for 1.0.
- **Consequences:** `ui-kit` owns the compact styling and the missing components. Moving to Camouflage later means its Material skin plus a compact Graft theme, with no screen changes.

### ADR-21 Fluent 2 look, matching Camouflage's Fluent skin (supersedes ADR-20's visual style; superseded by ADR-22)
- **Context:** ADR-20 styled Material 3 to look like JetBrains' New UI, which has no matching Camouflage skin. Camouflage's Fluent Skin is already designed: Fluent 2, desktop-first, compact sizes.
- **Decision:**
  - **Look:** Microsoft's Fluent 2. Fluent sizes (24 px small, 32 px medium), 4 px corners, neutral ramp plus brand ramp, Fluent shadows, subtle feedback, double-stroke focus, bottom-accent text fields, Fluent UI System Icons (MIT), and the platform font (Segoe UI Variable, SF Pro, Inter on Linux).
  - **Implementation:** a Material 3 theme in `ui-kit`, using the same token mapping as Camouflage's Fluent skin. No Fluent component library is used.
  - **Unchanged from ADR-20:** the layout, the JetBrains behaviors, and the Material 3 base.
- **Consequences:**
  - Switching to Camouflage later means using its `fluentSkin()` with no visual change.
  - Graft's needs feed back into the Fluent skin, which is "Later" in Camouflage's roadmap, so Graft may be what brings it forward.
  - Acrylic and Mica wait until after 1.0.

### ADR-22 Carbon look with Atom One colors, through Camouflage; `ui-kit` wraps Camouflage now (Accepted 2026-11-11; supersedes ADR-21 and ADR-20's Material 3 base; adopts ADR-9's `ui-kit` seam early)
- **Context:**
  - ADR-21 chose Fluent 2 drawn on Material 3, so a later switch to Camouflage's Fluent skin would change nothing visible. ADR-9 deferred Camouflage until it shipped a desktop skin.
  - Camouflage 0.1.0 is on Maven Central (Apache-2.0) with JVM builds of the core and all five skins, Carbon included, built on Compose 1.12.1 and Kotlin 2.4.20 (Graft: 1.12.2 and 2.4.21).
  - IBM Carbon suits a git client better than Fluent: it's made for dense, data-heavy desktop tools, with a strong grid, flat layered surfaces, square corners and an inset focus border that doesn't move the layout (Carbon Skin).
  - The author wants Atom's One color scheme: One Light and One Dark, familiar from editors.
  - `ui-kit` has no code yet (G0 Task 5), so switching now costs nothing; after G1 it would mean rewriting every component.
- **Decision:**
  - **Structure:** Carbon through Camouflage's `carbonSkin()`: square corners (pills only for tags and toggles), layered surfaces with no shadows except overlays, Carbon's 2 px inset focus border, Carbon's components and productive type sets in **IBM Plex Sans** (bundled, OFL-1.1).
  - **Colors:** **Atom One** (MIT). Graft Light uses One Light, Graft Dark uses One Dark, set through the theme's color roles instead of Carbon's families. One's blue is the accent. Values are used as published wherever they pass WCAG 2.2 AA; where they carry text and don't, a darker (light) or lighter (dark) step of the same hue is used. Camouflage's theme validator pins the result. The same palette gives the graph's lane colors and the diff syntax highlighting. Carbon's dark header becomes One's panel color.
  - **Density:** Graft's desktop sizes stay: 24 px (toolbars, panels, inline) and 32 px (dialogs, forms) controls, 24 px rows. Camouflage's Carbon skin builds only 48 px sizes, so Graft asks for a Carbon density option upstream ([camouflage#70](https://github.com/srctool/camouflage/issues/70)); until it ships, `ui-kit` sets the sizes through Camouflage's component themes.
  - **Icons:** Carbon's icon set (Apache-2.0); git icons it lacks (shelf, worktree, stack, stash) are drawn on its 16/20/32 grid. **Code font:** JetBrains Mono stays, with IBM Plex Mono as an option.
  - **Implementation:** `ui-kit` wraps Camouflage components behind Graft's own `Graft*` names and sets up the Camouflage provider with the Carbon skin and Graft's theme. Material 3 is no longer a dependency. Components Graft needs that Camouflage lacks (split pane, dock layout, speed search, document tabs, command palette, menu submenus, desktop scrollbars, table column resizing) are proposed to Camouflage ([camouflage#71–#78](https://github.com/srctool/camouflage/issues)) and built in `ui-kit` on Camouflage tokens until they ship. Git widgets stay Graft's.
  - **Unchanged:** ADR-20's GitKraken layout and JetBrains behaviors.
  - **Timing:** ADR-9 says to adopt between milestones; this is an accepted exception, since G0 has no UI code and the G0 spike doesn't use `ui-kit`. Sinew's seams are decided in a separate ADR before G0 Task 5 resumes.
- **Consequences:**
  - The import rule changes: only `ui-kit` imports `com.srctool.camouflage`, and nothing imports `androidx.compose.material3`.
  - Graft depends on a 0.x library whose API can change; the version is pinned, and Graft's needs feed back into Camouflage as its first real desktop app.
  - The light theme isn't exactly One Light where text needs AA. Fluent-only items are dropped: Mica, Acrylic, the double-stroke focus, Fluent shadows and the platform-font rule.

### ADR-23 Sinew for errors, view models and the network client (Accepted 2026-11-11; completes ADR-9's seams)
- **Context:**
  - ADR-9 kept seams so Graft could switch to Sinew later; ADR-22 switched the UI to Camouflage. Sinew 1.0 is published (Apache-2.0) with JVM builds on Graft's Kotlin, coroutines and Compose versions.
  - G0 Task 5 was writing Graft's own `GraftError` and `Result`; switching before that code is committed costs nothing.
- **Decision:**
  - **Errors:** every failure is a `SinewException` subclass and every fallible call returns Sinew's `Result` (`Success` / `Failure`). `GraftError` and Graft's own `Result` are dropped. Graft's exceptions live in `:core:exception` on `sinew-exception` (pure Kotlin, no Compose, native-image friendly).
  - **Layers in codes:** sources and repositories `R`, use cases `UC`, view models `VM`, Sinew's retry and polling helpers `UT`, app services `SV` once Sinew's libraries have it ([sinew-kotlin#96](https://github.com/srctool/sinew-kotlin/issues/96), [sinew-dart#91](https://github.com/srctool/sinew-dart/issues/91)); until then app-service errors use `UC`. Example: `GIT-R-PUSH-REJ`.
  - **Function codes** are 2–4 letters, as in Sinew (`PUSH`, `CLON`, `SHLV`). Module codes stay (`GIT`, `REPO`, `PRV`, `NET`, `DATA`, `UI`, `CLI`, `SSH`, `PRF`).
  - **Types:** HTTP failures use Sinew's built-in types through `processApiCall` (`NIC`, `RTO`, `UNA`, `FOR`, `NTF`, `CFL`, `RTL`, `SRV`, …). Git failures are Graft's own subclasses that keep argv, exit code and redacted stderr, with Graft's types: `REJ`, `AUTH`, `CONF`, `LOCK`, `NF`, `NET`, `TO`, `VER`, `EXIT`. Unreadable git output is Sinew's `ParseException` (`DF`), anything unmatched `UnexpectedException` (`GEN`); both go to the `CrashReporter`. Cancellation is rethrown, never a failure (`CANC` is dropped).
  - **Registry:** `:core:exception` keeps the registry of Graft's modules, functions and types, with the duplicate test.
  - **Messages:** every Graft exception carries an `ErrorMessage.Local` key with an English fallback. The UI renders it with a `Localizer`; `sinew-l10n` supplies English and Indonesian for Sinew's built-in keys from G1, used only from `:core:ui`.
  - **Crash reports:** G7's opt-in reporter implements Sinew's `CrashReporter` (no-op until then), previewed and redacted ([Privacy](07%20-%20Privacy.md)).
  - **View models:** Sinew's ViewModel base from `sinew-viewmodel` (on AndroidX ViewModel); Event → Action → State + Effect.
  - **Network:** `:core:network` configures `sinew-network`: `SinewHttp.client` with the **Ktor Java engine** passed explicitly (OkHttp excluded), Sinew's auth layer with a token source on the keychain, its redacting log, retry and `processApiCall`. Graft adds the ETag cache and the rate-limit status.
  - **Paging:** `sinew-paging` and `sinew-camouflage` for G6's PR, issue and notification lists, added when those tasks need them.
  - **Not used:** `sinew-storage` and `sinew-security` (secrets only in the OS keychain, ADR-7), `sinew-devtools` (a Material-based mobile overlay).
  - **Timing:** errors now (G0 Task 5, before its commit), view models in G1, the network client in G6, each in its own commit.
- **Consequences:**
  - The error invariant reads: every failure is a `SinewException` with a `MODULE-LAYER-FUNCTION-TYPE` code; git failures keep stderr and exit code.
  - A Sinew API change touches Graft; the version is pinned, and Graft's needs feed back into Sinew as its first desktop app.

### ADR-24 Module structure from the author's architecture board; build each milestone bottom to top (Accepted 2026-11-11; replaces the module graph in [Architecture](../01%20-%20Foundations/02%20-%20Architecture.md), amends ADR-4's and ADR-19's module names)
- **Context:**
  - The first module graph (`core-*`, `provider-github`, `ui-kit`, `feature-*`) mixed layers: `core-repo` held repositories, services and the session; `core-model` held errors that Sinew now provides; `ui-kit` wrapped a toolkit that Camouflage now is.
  - The author's architecture board (the source of Sinew's App Architecture) defines the shape Graft follows: *Modular App with Navigation*, with `:core` split into small configured-instance modules.
- **Decision:**
  - **Modules:**

    | Module | Holds | Compose |
    |---|---|---|
    | `:app:desktop` | startup, hand-wired DI, the root Camouflage provider, the window shell, and the **app services** (session and watcher, background fetch, auto-snapshot, inbox poller) | yes |
    | `:app:cli` | the `graft` CLI and `graft mcp` (native image) | no |
    | `:navigation` | routes for the center pane and tabs; features never import each other's screens | yes |
    | `:feature:*` | one area each: screens, their view models, and the area's own use cases | yes |
    | `:presentation` | **app-level state**, split by case (`WindowState`, `OperationsState`, `InboxState`, `ProviderState`) | no |
    | `:domain` | use cases shared by two or more features; created only when the first one is shared | no |
    | `:data:git`, `:data:app`, `:data:hosting`, `:data:hosting-github` | repositories with their sources: git-backed; app data (profiles, settings, workspaces); provider-neutral interfaces and models with the contract test kit; the GitHub implementation, the only module that says "GitHub" | no |
    | `:core:ui` | the theme (Carbon skin, Atom One colors, IBM Plex Sans, 24/32 px density), Graft-only shared components, stopgap components until Camouflage ships them, the `Localizer` | yes |
    | `:core:git`, `:core:network`, `:core:database`, `:core:security`, `:core:preference`, `:core:models`, `:core:exception`, `:core:notification`, `:core:configuration` | configured instances: the git runner, the HTTP client, the SQLite cache, the keychain, settings files, shared domain models, Graft's exceptions, desktop notifications, app folders | no |
  - **Dependencies point down only:** `app` → `navigation` → `feature` → `domain` / `presentation` / `data` → `core`. Features never import each other; `:data:*` never imports `:feature:*`, `:presentation` or `:core:ui`. The allowed edges are listed in `config/module-graph.txt`.
  - **Compose** only in `:core:ui`, `:feature:*`, `:navigation` and `:app:desktop`; `:app:cli` never depends on them (ADR-19's "shares the `core-*` modules" now reads: `:app:cli` shares `:data:*` and the Compose-free `:core:*`).
  - **Providers:** ADR-4's `provider-github` is `:data:hosting-github`; `core-provider` is `:data:hosting`.
  - **Not created** from the board's `:core` list: `:analytics` (Graft collects nothing, [Privacy](07%20-%20Privacy.md)); `:static`, `:extension`, `:utils` until something needs them.
  - **Build order:** within each milestone, tasks run bottom to top: `:core` → `:data` → use cases → `:presentation` → `:feature` UI → `:navigation` / `:app`. The author reviews each layer before the next is built on it, and the practices found in review go into the notes first. Every milestone still ends usable.
- **Consequences:**
  - G0's skeleton (Task 1) and the module-graph check are reworked to these modules before Task 5 resumes.
  - Plans list tasks per layer; a milestone plan shows the review point after the data layer.

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

### ADR-9 Build without Sinew and Camouflage first; adopt them later behind seams
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

### ADR-20 GitKraken layout, JetBrains style, on compact Material 3 (visual style superseded by ADR-21)
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

### ADR-21 Fluent 2 look, matching Camouflage's Fluent skin (supersedes ADR-20's visual style)
- **Context:** ADR-20 styled Material 3 to look like JetBrains' New UI, which has no matching Camouflage skin. Camouflage's Fluent Skin is already designed: Fluent 2, desktop-first, compact sizes.
- **Decision:**
  - **Look:** Microsoft's Fluent 2. Fluent sizes (24 px small, 32 px medium), 4 px corners, neutral ramp plus brand ramp, Fluent shadows, subtle feedback, double-stroke focus, bottom-accent text fields, Fluent UI System Icons (MIT), and the platform font (Segoe UI Variable, SF Pro, Inter on Linux).
  - **Implementation:** a Material 3 theme in `ui-kit`, using the same token mapping as Camouflage's Fluent skin. No Fluent component library is used.
  - **Unchanged from ADR-20:** the layout, the JetBrains behaviors, and the Material 3 base.
- **Consequences:**
  - Switching to Camouflage later means using its `fluentSkin()` with no visual change.
  - Graft's needs feed back into the Fluent skin, which is "Later" in Camouflage's roadmap, so Graft may be what brings it forward.
  - Acrylic and Mica wait until after 1.0.

# Graft: instructions for coding agents

Graft is an open-source (GPL-3.0-or-later) desktop Git client in Kotlin and Compose Multiplatform Desktop. It has a GitKraken-style commit graph, GitHub pull requests and issues, worktrees, submodules, JetBrains-style shelves and changelists, profiles, and a CLI and MCP server for AI agents.

These instructions apply to every agent working in this repository (Claude Code, Cursor, Copilot, …).

## 1. Design docs are the source of truth

Start with [`ARCHITECTURE.md`](ARCHITECTURE.md): one page with the module map and the invariants.

The design docs are written **before** code, in the author's notes (Obsidian), and mirrored into [`docs/design/`](docs/design/00%20-%20Overview.md) by `scripts/sync-design-docs.py`. **Read them in `docs/design/`.** Never edit `docs/design/` by hand: changes go into the notes, then get synced. If `docs/design/` is missing or looks out of date, say so and stop; don't guess the design.

Before starting any task, read:
1. `00 - Overview.md`: features, doc map, open questions.
2. The notes the task names (feature notes in `02 - Features/`, foundations in `01 - Foundations/`).
3. `03 - Project/02 - Decisions.md`: the ADRs. **Accepted ADRs are binding.** Superseded ones are marked in their heading.
4. `03 - Project/01 - Roadmap.md`: what belongs to the current milestone, and what doesn't.
5. `04 - Development/`: Project Setup, Conventions, Testing Strategy, CI, Release and Updates, Storage Formats, **Libraries** (use only the listed libraries), Platform Integration, Release Runbook. These are the detailed rules for building; this file summarizes them.
6. `01 - Foundations/07 - Threat Model.md` before touching git execution, credentials, SSH, config writing or the CLI/MCP.
7. `05 - Design/` (Screen Inventory, Keyboard Shortcuts, UX Writing) and `01 - Foundations/09 - Glossary.md` before any UI or user-facing text.

Rules:
- **Docs win over code.** If the code you're about to write disagrees with a note, the note is right, unless you've found a real problem in it.
- **Found a gap or a problem in the docs?** Don't silently diverge. Write it in the task's ledger entry (§3) as a *Ruling* (what you decided, why, and the cost if wrong) when it's small. When it changes a contract, an ADR, a storage format or the module graph, stop and ask.
- **Never edit the design docs yourself** unless the user asks you to.
- **Don't build ahead.** Only what the current milestone's plan lists. Later features have notes, but they wait.

## 2. Architecture rules (from the docs; checked in CI once G0 is done)

| Rule | Source |
|---|---|
| Module graph: `core-*` → `provider-*` / `feature-*` → `app-desktop` / `app-cli`, exactly as drawn. A new dependency edge needs an ADR | Architecture |
| `core-*` and `app-cli` never import Compose | Architecture, ADR-19 |
| Features never import each other; shared UI lives in `ui-kit` | Architecture |
| Screens use only `ui-kit` components; nothing outside `ui-kit` imports `androidx.compose.material3` | ADR-9, ADR-20, ADR-21 |
| Look: Fluent 2 at desktop density on Material 3 (24/32 px controls, 4 px corners, no ripple, no tonal elevation) | UI Design, ADR-21 |
| **Git is only reached through `GitRunner`** in `core-git`, running the `git` executable. No JGit, no libgit2, no shell (`sh -c`): argv only | Git Engine, ADR-2 |
| Machine-readable git output only (`-z`, `--porcelain=v2`, `--format` with `%x00`); `LC_ALL=C`, `GIT_TERMINAL_PROMPT=0`, the askpass/editor helpers | Git Engine |
| One mutating git operation at a time per repository (write queue); reads may run in parallel with `GIT_OPTIONAL_LOCKS=0` | ADR-3 |
| Never `push --force`: always `--force-with-lease --force-if-includes` | ADR-6 |
| Tokens and passphrases only in the OS keychain; never in files, logs, exceptions or test output | ADR-7 |
| Errors are `GraftError` with a `MODULE-LAYER-FUNCTION-TYPE` code (Sinew's format); keep git's stderr and exit code | Architecture, ADR-9 |
| Nothing outside `provider-github` says "GitHub"; use the neutral types (`ChangeRequest`, `Review`, `Check`, `Issue`, `Pipeline`) | Provider Model, ADR-4 |
| Sinew and Camouflage are **not** dependencies yet; keep the seams (`ui-kit`, `GraftViewModel`, `GraftError`, `core-network`) | ADR-9 |

## 3. How work is executed

Graft is built milestone by milestone (G0 … G10), and **task by task with a review after each task**.

1. **One plan per milestone** in `plans/<milestone>.md`: numbered tasks, each with its source notes, the files it touches, its tests, and a *Done when*. If a plan doesn't exist yet, write it first and stop for review before coding.
2. **One task at a time:**
   1. Read the task's notes.
   2. Write the tests first where it makes sense (parsers, graph layout, storage formats, error mapping).
   3. Implement.
   4. Run the checks (§5).
   5. Update `plans/progress.md`.
3. **Stop after each task** and report: what changed, test results (real output, failures included), any rulings, and what's next. Don't start the next task until the user says so.
4. **Ledger** `plans/progress.md`: one line per state change (`Task 3: drafted …`, `Task 3: complete (reviewed: …)`), plus `Ruling:` lines (`what — why — cost if wrong`). It's the record of decisions taken during implementation.
5. **Commits:** only when the user approves a task. Use Conventional Commits (`feat(core-git): …`), a DCO sign-off (`git commit -s`), and one commit per task unless asked otherwise. Never push unless asked.

## 4. Testing rules

- **Real `git`, in temporary directories.** Integration tests create throwaway repositories under a temp folder. **Never run git commands from tests against this repository or any of the user's real repositories.**
- **Sandbox the user's environment in every test that runs git or touches config.** Graft writes `~/.gitconfig`, `~/.ssh` and the keychain by design, so tests must never touch the developer's real ones:
  - `HOME` = a temp dir;
  - `GIT_CONFIG_GLOBAL` = a temp file;
  - `GIT_CONFIG_NOSYSTEM=1`;
  - `XDG_CONFIG_HOME` = a temp dir;
  - an in-memory fake keychain;
  - no real SSH agent (`SSH_AUTH_SOCK` unset).
- **Parsers:** fixtures recorded from real repos, including non-UTF-8 paths and messages, renames, binary files and submodules.
- **Graph layout:** pure unit tests (linear, branch and merge, octopus, several roots, out-of-order dates), plus a benchmark.
- **Providers:** recorded HTTP responses; no network in unit tests; no real tokens anywhere in fixtures.
- **Git versions:** integration tests must pass on the minimum supported Git (2.38) and the latest one.

## 5. Commands

The Gradle build is created in G0 Task 1. Until then, these don't exist.

| Task | Command |
|---|---|
| Build everything | `./gradlew build` |
| All checks (tests, lint, module graph, license headers) | `./gradlew check` |
| One module's tests | `./gradlew :core:git:test` |
| Run the app | `./gradlew :app:desktop:run` |
| Run the CLI | `./gradlew :app:cli:run --args="whoami"` |
| Format | `./gradlew spotlessApply` |

Before reporting a task done: `./gradlew check` passes. Say so with the output; if something fails, report it as failed.

## 6. Code conventions

- Package root: `com.srctool.graft` (`com.srctool.graft.core.git`, `…feature.graph`, …).
- Kotlin official code style, enforced by Spotless + ktlint. JVM toolchain 21.
- Every source file starts with:
  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  // Copyright (C) <year> Graft contributors
  ```
- Dependencies only through `gradle/libs.versions.toml`. New libraries must be GPL-3.0-compatible (Apache-2.0, MIT, BSD, LGPL, MPL-2.0, EPL-2.0 with its GPL secondary license). Never GPL-2.0-only, EPL-1.0, SSPL, "non-commercial" or proprietary.
- No code generation except what the docs name (SQLDelight). Hand-written sealed types.
- Comments explain *why*, not *what*. Public APIs in `core-*` get KDoc.
- No secrets in logs: redact tokens, `Authorization` headers, and credential-helper I/O.

## 7. Current state

| | |
|---|---|
| Milestone | **G0 Setup + spike**: plan in `plans/G0.md` |
| Next | G0 Task 2 (build checks) |
| Open decisions | final name checks (Naming), G0 spike confirming ADR-1 |

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **graft** (769 symbols, 761 relationships, 1 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/graft/context` | Codebase overview, check index freshness |
| `gitnexus://repo/graft/clusters` | All functional areas |
| `gitnexus://repo/graft/processes` | All execution flows |
| `gitnexus://repo/graft/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

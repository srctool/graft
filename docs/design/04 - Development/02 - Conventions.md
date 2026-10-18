---
tags: [graft, development]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 02 - Conventions

## 1. Why

Many people and agents will write Graft's code. One set of rules keeps it reading as one codebase, keeps the GPL license clean, and keeps secrets out of logs.

## 2. Code

| Topic | Rule |
|---|---|
| Packages | `com.srctool.graft.<area>` mirroring the module: `core.git`, `core.repo`, `feature.graph`, `provider.github`, `ui.kit`, `app.desktop`, `app.cli` |
| Style | Kotlin official style; Spotless + ktlint; 120 columns |
| Visibility | `core-*` modules use `explicitApi()`; everything is `internal` unless another module needs it |
| Types | hand-written sealed types for states and results; no code generation except SQLDelight |
| Errors | `Result<T>` + `GraftError` with `MODULE-LAYER-FUNCTION-TYPE` codes; no exceptions across module boundaries; view models never `try`/`catch` ([Architecture](../01%20-%20Foundations/02%20-%20Architecture.md)) |
| Coroutines | structured concurrency only; `Dispatchers.IO` for processes; no `GlobalScope`; every long operation cancellable |
| Git | only through `GitRunner`, with argv lists; never a shell string ([Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md)) |
| UI | screens use only `ui-kit` components; Fluent tokens from the theme, never hard-coded colors or sizes ([UI Design](../01%20-%20Foundations/06%20-%20UI%20Design.md)) |
| Strings | every user-facing string in resources (English and Indonesian from G8); no string concatenation for sentences |
| Comments | explain *why*; public `core-*` APIs get KDoc |

## 3. Error code registry

Codes are `MODULE-LAYER-FUNCTION-TYPE`, for example `GIT-E-PUSH-REJ`.

| Part | Values |
|---|---|
| Module | `GIT` (engine), `REPO`, `PRV` (provider), `NET`, `DATA`, `UI`, `CLI`, `SSH`, `PRF` (profiles) |
| Layer | `E` engine, `S` service, `V` view model, `P` provider |
| Function | short verb: `PUSH`, `PULL`, `LOG`, `SHLV`, `CLONE`, … |
| Type | `REJ` rejected, `AUTH`, `CONF` conflict, `LOCK`, `NF` not found, `TO` timeout, `NET`, `PARSE`, `VER` version, `CANC` cancelled |

New codes are added to `core-model`'s `ErrorCodes.kt` (the registry). A test fails on duplicate codes.

## 4. Logging and secrets

- One logger per module; levels: `debug` (dev only), `info`, `warn`, `error`.
- **Redacted at write time:** tokens (`ghp_…`, `github_pat_…`, `gho_…`, `Bearer …`), `Authorization` and `Cookie` headers, credential-helper input and output, askpass answers, and URLs with user:password.
- The git log tool window shows the argv, never the environment.
- Log files rotate (5 × 5 MB) in the OS log directory. Crash reports are opt-in and redact paths and remote URLs ([ADR-7](../03%20-%20Project/02%20-%20Decisions.md)).

## 5. License hygiene

- Every source file starts with:
  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  // Copyright (C) 2026 Graft contributors
  ```
- Dependencies are checked by `checkDependencyLicenses` against the allowed list in [Open Source](../03%20-%20Project/03%20-%20Open%20Source.md).
- Copied code (algorithms, snippets) only from compatible licenses, credited in `NOTICE`.

## 6. Git workflow

| Topic | Rule |
|---|---|
| Branches | `main` always green; work on `feat/…`, `fix/…`, `docs/…` branches |
| Commits | Conventional Commits with the module as scope: `feat(core-git): stream git log` |
| Sign-off | DCO: `git commit -s` on every commit; a CI check rejects commits without it |
| AI-assisted commits | keep the tool's `Co-Authored-By` trailer |
| PRs | small; one plan task per PR where possible; CI green; one review |
| Merging | squash merge; the PR title becomes the commit subject |
| Contract changes | an ADR in the design docs **before** the code that changes a contract, storage format, or module edge |

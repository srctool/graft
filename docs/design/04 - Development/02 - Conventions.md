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
| Packages | `com.srctool.graft.<area>` mirroring the module path: `core.git`, `data.git`, `data.hosting.github`, `feature.graph`, `core.ui`, `app.desktop`, `app.cli` |
| Style | Kotlin official style; Spotless + ktlint; 120 columns |
| Visibility | `:core:*` and `:data:*` use `explicitApi()`; a data module's public API is its repository interfaces and models, sources and DTOs stay `internal`; everything is `internal` unless another module needs it |
| Types | hand-written sealed types for states and results; no code generation except SQLDelight |
| Errors | Sinew's `Result<T>` + `SinewException` subclasses with `MODULE-LAYER-FUNCTION-TYPE` codes; no exceptions across module boundaries; cancellation is rethrown; view models never `try`/`catch` ([Architecture](../01%20-%20Foundations/02%20-%20Architecture.md), [ADR-23](../03%20-%20Project/02%20-%20Decisions.md)) |
| Coroutines | structured concurrency only; `Dispatchers.IO` for processes; no `GlobalScope`; every long operation cancellable |
| Git | only through `GitRunner`, with argv lists; never a shell string ([Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md)) |
| UI | screens use Camouflage components and `:core:ui`; Camouflage theme tokens, never hard-coded colors or sizes ([UI Design](../01%20-%20Foundations/06%20-%20UI%20Design.md)) |
| Strings | every user-facing string in resources (English and Indonesian from G8); no string concatenation for sentences |
| Comments | explain *why*; public APIs of `:core:*` and `:data:*` get KDoc |

## 3. Error code registry

Codes are Sinew's `MODULE-LAYER-FUNCTION-TYPE`, for example `GIT-R-PUSH-REJ` ([ADR-23](../03%20-%20Project/02%20-%20Decisions.md)).

| Part | Values |
|---|---|
| Module | `GIT` (git), `REPO`, `PRV` (provider), `NET`, `DATA`, `UI`, `CLI`, `SSH`, `PRF` (profiles) |
| Layer | Sinew's: `R` source or repository, `UC` use case, `VM` view model, `UT` Sinew's retry and polling helpers, `SV` app service (`ExceptionLayer.Service`, Sinew 1.1) |
| Function | 2–4 letters: `PUSH`, `PULL`, `LOG`, `SHLV`, `CLON`, … |
| Type | git failures: `REJ` rejected, `AUTH`, `CONF` conflict, `LOCK`, `NF` not found, `NET`, `TO` timeout, `VER` version, `EXIT` unrecognized non-zero exit. HTTP failures use Sinew's built-in types (`NIC`, `RTO`, `UNA`, `FOR`, `NTF`, `CFL`, `RTL`, `SRV`, …); unreadable output is `DF` and anything unmatched `GEN`. Cancellation is never a failure |

New modules, functions and types are added to the registry in `:core:exception`. A test fails on duplicate codes.

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
| Commits | Conventional Commits with the module as scope: `feat(core-git): stream git log` (the scope is the module path with dashes: `:core:git` → `core-git`) |
| Sign-off | DCO: `git commit -s` on every commit; a CI check rejects commits without it |
| AI-assisted commits | keep the tool's `Co-Authored-By` trailer |
| PRs | small; one plan task per PR where possible; CI green; one review |
| Merging | squash merge; the PR title becomes the commit subject |
| Contract changes | an ADR in the design docs **before** the code that changes a contract, storage format, or module edge |

---
tags: [graft, development, dependencies]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 07 - Libraries

## 1. Why

Agents and contributors shouldn't pick libraries ad hoc. This note fixes the choice for each need, with its license (GPL-3.0 compatibility, [Open Source](../03%20-%20Project/03%20-%20Open%20Source.md)), and whether it works in the native-image CLI ([ADR-19](../03%20-%20Project/02%20-%20Decisions.md)). Adding a runtime library not listed here needs a row here first.

**License column:** checked against the published license when written. `checkDependencyLicenses` is the real gate ([Project Setup](01%20-%20Project%20Setup.md)). **Verify** marks rows to confirm when the library is added. Test-only and build-only tools aren't shipped, so the GPL compatibility rule applies to the runtime classpath.

## 2. Runtime libraries

| Need | Choice | License | CLI (native image) | Notes |
|---|---|---|---|---|
| Language, coroutines | Kotlin stdlib, `kotlinx-coroutines` | Apache-2.0 | yes | |
| JSON | `kotlinx-serialization-json` | Apache-2.0 | yes | no reflection, so native-image friendly |
| UI | Compose Multiplatform (desktop), Material 3 | Apache-2.0 | n/a | only through `ui-kit` |
| HTTP | Ktor client with the **Java** engine (JDK `HttpClient`) | Apache-2.0 | yes | HTTP/2, no extra native code; ETag cache in `core-network` |
| GitHub GraphQL | hand-written query strings + `kotlinx-serialization` DTOs | n/a | yes | Apollo Kotlin considered and not chosen: code generation (Conventions: no codegen except SQLDelight) |
| Database | SQLDelight + `sqlite-jdbc` (xerial) | Apache-2.0 | yes (ships native-image metadata) | cache and app state |
| OS keychain | `java-keyring` (macOS Keychain, Windows Credential Manager, Linux Secret Service) | BSD-3-Clause; Linux backend via `secret-service` + `dbus-java` (MIT), **verify** | needs JNA reachability config | fallback: own JNA bindings behind `SecretStore` if it falls short |
| Native interop | JNA | Apache-2.0 / LGPL-2.1 (dual) | needs config | used by keychain, file watcher, platform calls |
| File watching | `io.methvin:directory-watcher` | Apache-2.0 | yes, with JNA config | native FSEvents on macOS, where the JDK's `WatchService` only polls ([Platform Integration](08%20-%20Platform%20Integration.md)) |
| System info (battery, sleep) | OSHI | MIT | n/a (app only) | background fetch is off on battery |
| Word-level diff | `java-diff-utils` | Apache-2.0 | yes | line diffs come from `git diff` |
| Syntax highlighting | **to decide in G1:** `dev.snipme:highlights` (Apache-2.0, pure Kotlin, fewer languages) vs. TextMate grammars via `tm4e` (EPL-2.0: **verify** GPL secondary license) vs. tree-sitter (`ktreesitter`, MIT, native code) | see choice | n/a | pick by language coverage and speed on a 5,000-line diff |
| Markdown (PRs, issues) | `multiplatform-markdown-renderer` (mikepenz) on JetBrains' `markdown` parser | Apache-2.0 | n/a | raw HTML not rendered ([Threat Model](../01%20-%20Foundations/07%20-%20Threat%20Model.md) T5) |
| Images (avatars) | Coil 3 | Apache-2.0 | n/a | disk cache in the OS cache dir |
| Logging | `kotlin-logging` + SLF4J + Logback | Apache-2.0; Logback EPL-1.0 / **LGPL-2.1** (use under LGPL) | yes | redaction in a Logback encoder ([Conventions](02%20-%20Conventions.md)) |
| CLI parsing | Clikt | Apache-2.0 | yes | |
| MCP server | the official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) | permissive, **verify** | **verify** (Ktor/serialization based) | stdio transport only |
| Updates | Sparkle (macOS), WinSparkle (Windows), or Conveyor's updater | MIT / MIT / Conveyor terms | n/a | decided in G8 ([Release and Updates](05%20-%20Release%20and%20Updates.md)) |

**Not used:** JGit and libgit2 ([ADR-2](../03%20-%20Project/02%20-%20Decisions.md)); Jewel ([ADR-20](../03%20-%20Project/02%20-%20Decisions.md)); a DI framework (hand wiring, [Architecture](../01%20-%20Foundations/02%20-%20Architecture.md)); Sinew and Camouflage, until [ADR-9](../03%20-%20Project/02%20-%20Decisions.md)'s adoption point.

## 3. Assets

| Asset | Source | License |
|---|---|---|
| Inter (Linux UI font) | rsms/inter | OFL-1.1 |
| JetBrains Mono | JetBrains | OFL-1.1 |
| Fluent UI System Icons | Microsoft | MIT |
| `.gitignore` templates | github/gitignore | CC0-1.0 |
| Secret-detection rules | own; patterns may be adapted from gitleaks (MIT) with credit in `NOTICE` | MIT |

## 4. Test and build tools (not shipped)

| Need | Choice |
|---|---|
| Test runner | JUnit 5 (via `kotlin.test`) |
| Flow testing | Turbine |
| Assertions | `kotlin.test` + Kotest assertions |
| Test doubles | hand-written fakes (no mocking library by default) |
| Screenshot tests | Roborazzi (Compose Desktop) |
| Benchmarks | `kotlinx-benchmark` (JMH) |
| Formatting | Spotless + ktlint |
| Native image | GraalVM Native Build Tools Gradle plugin |
| Packaging | Compose Gradle plugin (`jpackage`), or Conveyor |
| License report | Gradle license report plugin, driving `checkDependencyLicenses` |

## 5. Edge cases

| Case | Decided behavior |
|---|---|
| A library lacks native-image support | the CLI doesn't use it; if a CLI command needs it, add reachability metadata or pick another library (new row first) |
| A transitive dependency has an incompatible license | the check fails; exclude it or replace the library |
| A library is abandoned | keep it behind a Graft interface (`SecretStore`, `FileWatcher`, `Highlighter`, `MarkdownView`) so replacing it touches one module |

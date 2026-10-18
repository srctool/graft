---
tags: [graft, development]
status: seedling
milestone: G0
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 01 - Project Setup

## 1. Why

G0 proves the skeleton before any feature has content: one Gradle module per box in the [Architecture](../01%20-%20Foundations/02%20-%20Architecture.md) graph, a dependency graph that CI enforces, two apps (desktop and CLI), and a build that works the same on macOS, Windows and Linux. Getting this right first saves fighting it in every later milestone.

## 2. Shape

```
graft/
├─ build-logic/                 convention plugins (included build)
│  └─ src/main/kotlin/
│     ├─ graft.kotlin-library.gradle.kts
│     ├─ graft.compose-library.gradle.kts
│     ├─ graft.compose-app.gradle.kts
│     ├─ graft.native-cli.gradle.kts
│     └─ checks/                module-graph, import-rule, license-header tasks
├─ config/
│  └─ module-graph.txt          allowed project dependencies, one "from -> to" per line
├─ gradle/
│  ├─ libs.versions.toml        every dependency and plugin version
│  └─ wrapper/
├─ core-model/  core-git/  core-repo/  core-provider/  core-network/  core-data/
├─ provider-github/
├─ ui-kit/
├─ feature-graph/  feature-workingcopy/  feature-shelves/  feature-worktrees/
├─ feature-submodules/  feature-changerequests/  feature-issues/
├─ app-desktop/                 Compose Desktop app
├─ app-cli/                     graft CLI + graft mcp (GraalVM native image)
├─ fixtures/                    recorded repos and API responses (see Testing Strategy)
├─ plans/                       milestone plans and the progress ledger (agent workflow)
├─ AGENTS.md  CLAUDE.md         agent instructions
└─ settings.gradle.kts  build.gradle.kts  gradle.properties
```

New feature modules from notes 11–32 (`feature-profiles`, `feature-history`, …) are added in the milestone that builds them, each with its edges added to `module-graph.txt` first.

## 3. API

### 3.1 Toolchain

| Tool | Version | Why |
|---|---|---|
| JDK | **21** (Temurin recommended) to run Gradle; the Kotlin toolchain is pinned to 21 | Compose Desktop and jpackage need a full JDK |
| GraalVM | 21 (Community), only for `app-cli` native builds | [ADR-19](../03%20-%20Project/02%20-%20Decisions.md) |
| Gradle | via the wrapper only (latest stable 9.x) | never a system Gradle |
| Kotlin, Compose Multiplatform | latest stable pair, pinned in `libs.versions.toml` | |
| Git | ≥ 2.38 for development and tests | [Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md) |

`settings.gradle.kts` applies the **foojay toolchain resolver**, so Gradle downloads JDK 21 for compilation if it's missing. Gradle itself still needs some JDK to start.

### 3.2 `settings.gradle.kts`

- `pluginManagement { includeBuild("build-logic") }`
- `dependencyResolutionManagement` with `repositoriesMode = FAIL_ON_PROJECT_REPOS`: only `mavenCentral()` and `google()`.
- `rootProject.name = "graft"`; every module included by name.
- Type-safe project accessors (`projects.coreGit`).

### 3.3 Convention plugins

| Plugin | Applies | Used by |
|---|---|---|
| `graft.kotlin-library` | Kotlin JVM, toolchain 21, `explicitApi()` for `core-*`, JUnit 5, Spotless + ktlint, license header check | `core-*`, `provider-*` |
| `graft.compose-library` | the above + Compose Multiplatform (desktop) + compose compiler | `ui-kit`, `feature-*` |
| `graft.compose-app` | the above + `compose.desktop.application` (main class, package formats, app icon, version) | `app-desktop` |
| `graft.native-cli` | `graft.kotlin-library` + `application` + GraalVM native-image plugin (reflection config for parsers and SQLDelight) | `app-cli` |

### 3.4 Build checks (wired into `check`)

| Task | Fails when |
|---|---|
| `checkModuleGraph` | a project dependency isn't listed in `config/module-graph.txt` |
| `checkImports` | a file outside `ui-kit` imports `androidx.compose.material3`, or a file in `core-*` / `app-cli` imports `androidx.compose` |
| `checkLicenseHeaders` | a `.kt` / `.kts` file lacks the SPDX header ([Conventions](02%20-%20Conventions.md)) |
| `checkDependencyLicenses` | a dependency's license isn't on the GPL-3.0-compatible list ([Open Source](../03%20-%20Project/03%20-%20Open%20Source.md)) |

### 3.5 Everyday commands

| Task | Command |
|---|---|
| Build | `./gradlew build` |
| All checks | `./gradlew check` |
| One module | `./gradlew :core-git:test` |
| Run the app | `./gradlew :app-desktop:run` |
| Run the CLI (JVM) | `./gradlew :app-cli:run --args="whoami"` |
| Native CLI | `./gradlew :app-cli:nativeCompile` |
| Format | `./gradlew spotlessApply` |
| Packages for this OS | `./gradlew :app-desktop:packageDistributionForCurrentOS` |

## 4. Build steps

1. Wrapper, `settings.gradle.kts`, the version catalog, and `build-logic` with the four plugins.
2. All modules from the Architecture graph, empty except a placeholder, wired exactly as drawn. `module-graph.txt` is copied from the diagram.
3. The four checks, each with a failing and a passing fixture.
4. `app-desktop` opens an empty "Graft" window; `app-cli` prints its version.

### Contributor setup

| OS | Steps |
|---|---|
| macOS | `brew install --cask temurin@21`; Git from Xcode Command Line Tools or Homebrew; optional GraalVM 21 for native CLI builds |
| Windows | Temurin 21 MSI, or `winget install EclipseAdoptium.Temurin.21.JDK`; Git for Windows; long paths on (`git config --system core.longpaths true`) |
| Linux | distro OpenJDK 21 or Temurin; distro Git ≥ 2.38; `libsecret` for the keychain tests |

Then `./gradlew build`. The first run downloads Gradle and the dependencies.

## 5. Edge cases

| Case | Decided behavior |
|---|---|
| No JDK on `PATH` (`java` not found) | Gradle can't start; the README's first line says to install JDK 21. The toolchain resolver only helps once Gradle runs |
| Several JDKs installed | `JAVA_HOME` picks Gradle's JVM; compilation always uses the toolchain (21) |
| Path with spaces (macOS iCloud, Windows user folders) | every build script and test quotes paths; CI runs one job in a folder with a space in its name |
| Apple Silicon vs Intel | packages built per architecture (`macos-arm64`, `macos-x64`); the native CLI per OS and architecture |

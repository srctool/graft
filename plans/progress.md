# Progress ledger

One line per state change. `Ruling:` lines record decisions made during implementation: what — why — cost if wrong.

## G0: Setup + spike (plan: `plans/G0.md`)

G0: plan drafted (8 tasks). Awaiting user review.
G0: plan approved by the user (2026-10-18). `git init` done (branch `main`).
Task 1: complete, awaiting review. Gradle 9.8.1 wrapper (with distribution SHA-256), Kotlin 2.4.21, Compose Multiplatform 1.12.2, Spotless 8.10.4 + ktlint 1.8.0; `build-logic` with `graft.spotless`, `graft.kotlin-library`, `graft.compose-library` and `graft.compose-app`; 17 modules wired exactly as the Architecture graph draws them; `app-desktop` opens an empty "Graft" window on Temurin 21.0.12. `./gradlew build` and `./gradlew check` pass on macOS (arm64).
Ruling: the Gradle daemon is pinned to JDK 21 via `gradle/gradle-daemon-jvm.properties` (auto-provisioned through foojay), and `compose.desktop.application.javaHome` is the toolchain 21 launcher — Project Setup says JDK 21 runs Gradle and the app; without the pin, Gradle and `:app-desktop:run` used whichever JDK started the wrapper (JBR 25 here) — cost if wrong: one-line revert per file.
Ruling: ktlint `intellij_idea` code style, not `ktlint_official` — Conventions says "Kotlin official style", which is the IntelliJ/Kotlin coding conventions style — cost if wrong: one `.editorconfig` line plus a `spotlessApply`.
Ruling: `graft.native-cli` (listed in Project Setup) is not created yet; `app-cli` uses `graft.kotlin-library` as the G0 plan says, and doesn't print its version yet — GraalVM and the CLI entry point arrive with the CLI milestone; adding a native-image plugin now would be unused config — cost if wrong: the plugin is a small file to add later.
Ruling: JUnit 5 (BOM 5.14.4) through `kotlin.test`, not JUnit 6 — the Libraries note names JUnit 5 — cost if wrong: one catalog version.
Ruling: Compose dependencies are catalog coordinates (`org.jetbrains.compose.runtime:runtime` …), not the plugin's `compose.*` accessors, so every version lives in `libs.versions.toml`; Material 3 isn't added until `ui-kit` gets its first component — cost if wrong: none at runtime.
Note: the two Gradle 10 deprecation warnings at configuration (`Configuration.setVisible`, `HotReloadUsageType` attribute) come from the Compose Gradle plugin 1.12.2, not Graft's scripts.
Note: no JDK 21 was installed on the dev machine; JBR 25 from Android Studio started the wrapper. Installing Temurin 21 (`brew install --cask temurin@21`) is the documented setup.
Task 1: revised on review — module folders grouped by layer (`core/git`, `feature/graph`, `app/desktop`, `provider/github`, `ui/kit`); `./gradlew check` passes and the window still opens.
Ruling: only the folders are nested; Gradle project names stay `:core-git` etc. (set with `projectDir` in `settings.gradle.kts`) — the user asked for folder-in-folder; keeping the names leaves the commands, `projects.coreGit` accessors, ARCHITECTURE.md and `module-graph.txt` valid. The Project Setup note's tree (§2) still draws flat folders and should be updated in the notes — cost if wrong: renaming to `:core:git` later touches every build file and the docs.
Task 1: revised again on review — Gradle projects renamed to nested paths (`:core:git`, `:feature:graph`, `:app:desktop`, `:provider:github`, `:ui:kit`); accessors are `projects.core.git`; AGENTS.md commands updated.
Ruling: artifacts are named from the full path (`graft-core-git.jar`) via `base.archivesName`, and `explicitApi()` keys on the `:core:` path — leaf names like `git`, `model`, `provider` are ambiguous alone, and `:core:provider` would share its name with the `:provider` folder project — cost if wrong: one line in `graft.kotlin-library`.
Ruling: supersedes the previous ruling's flat names. Module names in ARCHITECTURE.md and the notes (`core-git`) now read as labels for `:core:git`; the Project Setup note (tree and commands) should be updated in the notes — cost if wrong: confusion between the two spellings until the notes change.
Task 1: Android Studio rewrote root `build.gradle.kts` on import (Kotlin 2.3.20, JVM toolchain 8, a project repository); restored to the Spotless-only version at the user's request. `./gradlew clean check` passes; `:app:desktop:run` opens the "Graft" window.
Task 1: approved by the user (2026-10-18).
Task 1: committed as d592c55. Repo indexed with GitNexus 1.6.12 (769 nodes, 761 edges); it added its block to AGENTS.md and CLAUDE.md and its skills under `.claude/skills/`.
Tracking: GitHub milestones G0–G10, one milestone issue each plus 108 task issues (#1–#119) in srctool/graft, all in the private org project "Graft" (srctool project 9) with the same Status options as Sinew. G0 tasks map to #2–#9 (Task 1 = #2, closed). G1–G10 task issues are drafts from the Roadmap and feature notes; each milestone plan revises them.

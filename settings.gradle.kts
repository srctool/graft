// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "graft"

include(
    ":module:core:exception",
    ":module:core:git",
    ":module:core:network",
    ":module:core:database",
    ":module:core:security",
    ":module:core:preference",
    ":module:core:notification",
    ":module:core:configuration",
    ":module:core:watcher",
    ":module:core:ui",
    ":module:data:git",
    ":module:data:app",
    ":module:data:hosting",
    ":module:data:hosting-github",
    ":module:presentation",
    ":module:feature:home",
    ":module:feature:graph",
    ":module:feature:workingcopy",
    ":module:feature:shelves",
    ":module:feature:worktrees",
    ":module:feature:submodules",
    ":module:feature:changerequests",
    ":module:feature:issues",
    ":module:navigation",
    ":app:desktop",
    ":app:cli",
)

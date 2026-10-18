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
    ":core:model",
    ":core:git",
    ":core:repo",
    ":core:provider",
    ":core:network",
    ":core:data",
    ":provider:github",
    ":ui:kit",
    ":feature:graph",
    ":feature:workingcopy",
    ":feature:shelves",
    ":feature:worktrees",
    ":feature:submodules",
    ":feature:changerequests",
    ":feature:issues",
    ":app:desktop",
    ":app:cli",
)

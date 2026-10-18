// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Formatting for every project. Each project formats its own sources and build script; the root also covers
// build-logic, which isn't a subproject.

plugins {
    id("com.diffplug.spotless")
}

val ktlintVersion =
    the<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("ktlint")
        .get()
        .requiredVersion

spotless {
    kotlin {
        target("src/**/*.kt")
        if (project == rootProject) target("build-logic/src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        if (project ==
            rootProject
        ) {
            target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        }
        ktlint(ktlintVersion)
    }
}

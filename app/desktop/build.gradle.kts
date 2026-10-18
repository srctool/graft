// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-app")
}

dependencies {
    implementation(projects.feature.graph)
    implementation(projects.feature.workingcopy)
    implementation(projects.feature.shelves)
    implementation(projects.feature.worktrees)
    implementation(projects.feature.submodules)
    implementation(projects.feature.changerequests)
    implementation(projects.feature.issues)
    implementation(projects.provider.github)
}

compose.desktop {
    application {
        mainClass = "com.srctool.graft.app.desktop.MainKt"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.module.feature.graph)
    implementation(projects.module.feature.workingcopy)
    implementation(projects.module.feature.shelves)
    implementation(projects.module.feature.worktrees)
    implementation(projects.module.feature.submodules)
    implementation(projects.module.feature.changerequests)
    implementation(projects.module.feature.issues)
    implementation(projects.module.presentation)
    implementation(projects.module.core.ui)
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.module.feature.home)
    implementation(projects.module.feature.graph)
    implementation(projects.module.feature.workingcopy)
    implementation(projects.module.feature.shelves)
    implementation(projects.module.feature.worktrees)
    implementation(projects.module.feature.submodules)
    implementation(projects.module.feature.changerequests)
    implementation(projects.module.feature.issues)
    implementation(projects.module.presentation)
    implementation(projects.module.core.ui)
    // ADR-26: the app hands navigation the data modules it built; navigation passes their api to features.
    implementation(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
}

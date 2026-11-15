// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.presentation)
    implementation(projects.data.git)
    implementation(projects.data.app)
    implementation(projects.data.hosting)
    implementation(projects.core.ui)
    implementation(projects.core.models)
    implementation(projects.core.exception)
}

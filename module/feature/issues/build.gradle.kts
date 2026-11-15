// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.module.presentation)
    implementation(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    implementation(projects.module.core.ui)
    implementation(projects.module.core.models)
    implementation(projects.module.core.exception)
}

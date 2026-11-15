// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    // The state types carry data:git's models (a tab's repository, its refs) and Flow.
    api(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    implementation(projects.module.core.exception)
    testImplementation(libs.kotlinx.coroutines.test)
}

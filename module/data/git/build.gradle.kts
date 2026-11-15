// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    // The git runner is this module's source; its models are the public API (ADR-24).
    implementation(projects.module.core.git)
    implementation(projects.module.core.exception)

    testImplementation(testFixtures(projects.module.core.git))
    testImplementation(libs.kotlinx.coroutines.test)
}

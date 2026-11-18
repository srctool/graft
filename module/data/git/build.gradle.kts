// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    // The api returns Sinew's Result and streams with Flow; the git runner is this module's source, wired in through di.
    api(platform(libs.sinew.bom))
    api(libs.sinew.models)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.module.core.git)
    // ADR-28: the di package's Koin module.
    api(platform(libs.koin.bom))
    api(libs.koin.core)
    testImplementation(libs.koin.test)
    implementation(projects.module.core.exception)

    testImplementation(testFixtures(projects.module.core.git))
    testImplementation(libs.kotlinx.coroutines.test)
}

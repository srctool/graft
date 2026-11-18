// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // ADR-28: the di package's Koin module.
    api(platform(libs.koin.bom))
    api(libs.koin.core)
    testImplementation(libs.koin.test)
    // The api returns Sinew's Result and stores its models as JSON through core:preference's versioned files.
    api(platform(libs.sinew.bom))
    api(libs.sinew.models)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(projects.module.core.database)
    implementation(projects.module.core.preference)
    implementation(projects.module.core.security)
    implementation(projects.module.core.configuration)
    implementation(projects.module.core.exception)

    testImplementation(libs.kotlinx.coroutines.test)
}

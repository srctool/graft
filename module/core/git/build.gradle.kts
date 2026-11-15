// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
    `java-test-fixtures`
}

dependencies {
    // GitRunner returns Sinew's Result with Graft's GitExceptions, and streams with Flow.
    api(projects.module.core.exception)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.module.core.models)

    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)

    testImplementation(libs.kotlinx.coroutines.test)
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.directory.watcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

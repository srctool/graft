// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    api(platform(libs.sinew.bom))
    api(libs.sinew.exception)
    implementation(projects.core.models)
}

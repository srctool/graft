// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    implementation(projects.module.core.database)
    implementation(projects.module.core.preference)
    implementation(projects.module.core.security)
    implementation(projects.module.core.configuration)
    implementation(projects.module.core.exception)
}

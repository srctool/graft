// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    implementation(projects.module.data.hosting)
    implementation(projects.module.core.network)
    implementation(projects.module.core.database)
    implementation(projects.module.core.models)
    implementation(projects.module.core.exception)
}

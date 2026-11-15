// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.kotlin-library")
}

dependencies {
    implementation(projects.data.git)
    implementation(projects.data.app)
    implementation(projects.data.hosting)
    implementation(projects.data.hostingGithub)
    implementation(projects.core.git)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.security)
    implementation(projects.core.preference)
    implementation(projects.core.models)
    implementation(projects.core.exception)
    implementation(projects.core.configuration)
}

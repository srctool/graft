// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-app")
}

dependencies {
    implementation(projects.navigation)
    implementation(projects.presentation)
    implementation(projects.data.git)
    implementation(projects.data.app)
    implementation(projects.data.hosting)
    implementation(projects.data.hostingGithub)
    implementation(projects.core.ui)
    implementation(projects.core.git)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.security)
    implementation(projects.core.preference)
    implementation(projects.core.models)
    implementation(projects.core.exception)
    implementation(projects.core.notification)
    implementation(projects.core.configuration)
}

compose.desktop {
    application {
        mainClass = "com.srctool.graft.app.desktop.MainKt"
    }
}

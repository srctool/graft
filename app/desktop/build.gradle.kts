// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-app")
}

dependencies {
    implementation(projects.module.navigation)
    implementation(projects.module.presentation)
    implementation(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    implementation(projects.module.data.hostingGithub)
    implementation(projects.module.core.ui)
    implementation(projects.module.core.git)
    implementation(projects.module.core.network)
    implementation(projects.module.core.database)
    implementation(projects.module.core.security)
    implementation(projects.module.core.preference)
    implementation(projects.module.core.models)
    implementation(projects.module.core.exception)
    implementation(projects.module.core.notification)
    implementation(projects.module.core.configuration)
}

compose.desktop {
    application {
        mainClass = "com.srctool.graft.app.desktop.MainKt"
    }
}

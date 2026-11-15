// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-app")
}

dependencies {
    implementation(projects.module.navigation)
    // G0 Task 8 spike only; removed in G1 (config/module-graph.txt).
    implementation(projects.module.feature.graph)
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
    implementation(projects.module.core.exception)
    implementation(projects.module.core.notification)
    implementation(projects.module.core.configuration)
    implementation(projects.module.core.watcher)
    // Dispatchers.Main on the Swing event thread, which Compose Desktop draws on; view models run there.
    runtimeOnly(libs.kotlinx.coroutines.swing)
    implementation(platform(libs.sinew.bom))
    implementation(libs.sinew.presentation)

    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.srctool.graft.app.desktop.MainKt"
    }
}

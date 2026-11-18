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
    // ADR-28: the app starts Koin with every module.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    testImplementation(libs.koin.test)

    testImplementation(libs.kotlinx.coroutines.test)
    // The graph measurement (G1 Task 13) drives the real screen against a sandboxed git.
    testImplementation(testFixtures(projects.module.core.git))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
}

compose.desktop {
    application {
        mainClass = "com.srctool.graft.app.desktop.MainKt"
    }
}

// The graph measurement (GRAFT_MEASURE_REPO) runs with the app's default heap (a quarter of RAM), as G0 measured.
tasks.withType<Test>().configureEach {
    if (System.getenv("GRAFT_MEASURE_REPO") != null) maxHeapSize = "4g"
}

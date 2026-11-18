// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.module.presentation)
    implementation(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    implementation(projects.module.core.ui)
    implementation(projects.module.core.exception)
    implementation(platform(libs.sinew.bom))
    implementation(libs.sinew.viewmodel)
    // ADR-28: the di package's Koin module, and view models through koinViewModel.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

dependencies {
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.roborazzi.compose.desktop)
}

// Screenshot goldens, as in :module:feature:graph: compared on Linux only, recorded again with `-PupdateGoldens`.
tasks.withType<Test>().configureEach {
    val linux = System.getProperty("os.name").startsWith("Linux")
    val update = providers.gradleProperty("updateGoldens").isPresent
    when {
        update -> systemProperty("roborazzi.test.record", "true")
        linux -> systemProperty("roborazzi.test.verify", "true")
    }
    inputs.files(fileTree("src/test/snapshots")).withPropertyName("goldens")
}

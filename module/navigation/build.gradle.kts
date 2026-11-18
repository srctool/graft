// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.module.feature.home)
    implementation(projects.module.feature.graph)
    implementation(projects.module.feature.workingcopy)
    implementation(projects.module.feature.shelves)
    implementation(projects.module.feature.worktrees)
    implementation(projects.module.feature.submodules)
    implementation(projects.module.feature.changerequests)
    implementation(projects.module.feature.issues)
    implementation(projects.module.presentation)
    implementation(projects.module.core.ui)
    // ADR-26: the app hands navigation the data modules it built; navigation passes their api to features.
    implementation(projects.module.data.git)
    implementation(projects.module.data.app)
    implementation(projects.module.data.hosting)
    // ADR-28: navigation re-exports the Koin modules of the features it hosts.
    api(platform(libs.koin.bom))
    api(libs.koin.core)
    // The Settings and About hosts (D1): their view models, and About's license report.
    implementation(platform(libs.sinew.bom))
    implementation(libs.sinew.viewmodel)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
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

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    // The theme and components are drawn by Camouflage's Carbon skin; screens use Camouflage too, through this
    // module's provider.
    api(platform(libs.camouflage.bom))
    api(libs.camouflage.core)
    api(libs.camouflage.skin.carbon)
    api(platform(libs.sinew.bom))
    api(libs.sinew.l10n)
    implementation(projects.module.core.exception)

    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.roborazzi.compose.desktop)
}

// Screenshot goldens (Testing Strategy): compared on Linux only, where they're recorded with the bundled fonts, since
// font rendering differs between OSes; `-PupdateGoldens` records them again. Elsewhere the screens still render, so a
// crash is caught, but nothing is compared.
tasks.withType<Test>().configureEach {
    val linux = System.getProperty("os.name").startsWith("Linux")
    val update = providers.gradleProperty("updateGoldens").isPresent
    when {
        update -> systemProperty("roborazzi.test.record", "true")
        linux -> systemProperty("roborazzi.test.verify", "true")
    }
    inputs.dir("src/test/snapshots").optional()
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// The desktop application: app-desktop. The module sets its own main class.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        // Run and package on the toolchain JDK, not whatever JDK happens to run Gradle.
        javaHome =
            javaToolchains
                .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
                .get()
                .metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            packageName = "Graft"
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        }
    }
}

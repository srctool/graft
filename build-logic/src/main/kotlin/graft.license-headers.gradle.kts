// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Repository-wide license header check, applied to the root project and wired into `check`.

import com.srctool.graft.buildlogic.checks.CheckLicenseHeadersTask

plugins {
    base
}

val checkLicenseHeaders =
    tasks.register<CheckLicenseHeadersTask>("checkLicenseHeaders") {
        group = "verification"
        description = "Fails when a .kt / .kts file lacks the SPDX license header."
        sources.from(
            fileTree(layout.projectDirectory) {
                include("**/*.kt", "**/*.kts")
                exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**", ".idea/**", "**/node_modules/**")
            },
        )
        rootDir = layout.projectDirectory
        report = layout.buildDirectory.file("reports/checks/license-headers.txt")
    }

tasks.named("check") {
    dependsOn(checkLicenseHeaders)
}

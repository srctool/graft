// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Per-module architecture checks, wired into `check`: module graph and import rules.

import com.srctool.graft.buildlogic.checks.CheckImportsTask
import com.srctool.graft.buildlogic.checks.CheckModuleGraphTask

plugins {
    base
}

val checkModuleGraph =
    tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
        group = "verification"
        description = "Fails on a project dependency that config/module-graph.txt doesn't list."
        projectPath = project.path
        // Every configuration counts, test ones included: a test-only edge is still an edge.
        projectDependencies =
            provider {
                configurations
                    .flatMap { it.dependencies.withType<ProjectDependency>() }
                    .map { it.path }
                    .toSet()
            }
        graphFile = rootProject.layout.projectDirectory.file("config/module-graph.txt")
        report = layout.buildDirectory.file("reports/checks/module-graph.txt")
    }

val checkImports =
    tasks.register<CheckImportsTask>("checkImports") {
        group = "verification"
        description = "Fails on Compose or Material 3 imports where the Architecture forbids them."
        projectPath = project.path
        sources.from(fileTree("src") { include("**/*.kt", "**/*.kts") })
        projectDir = layout.projectDirectory
        report = layout.buildDirectory.file("reports/checks/imports.txt")
    }

tasks.named("check") {
    dependsOn(checkModuleGraph, checkImports)
}

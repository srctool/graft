// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.buildlogic.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when this project depends on a project that `config/module-graph.txt` doesn't allow. */
@CacheableTask
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val projectDependencies: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val graphFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val graph = ModuleGraph.parse(graphFile.get().asFile.readText())
        val from = projectPath.get()
        val bad = graph.violations(from, projectDependencies.get())
        if (bad.isNotEmpty()) {
            val allowed = graph.allowedFrom(from).ifEmpty { listOf("nothing") }.joinToString()
            throw GradleException(
                buildString {
                    appendLine("Module graph violation: these edges aren't in config/module-graph.txt:")
                    bad.forEach { appendLine("  $from -> $it") }
                    appendLine("$from may depend on: $allowed.")
                    append("A new edge needs an ADR first (AGENTS.md §2), then a line in config/module-graph.txt.")
                },
            )
        }
        report.get().asFile.writeText("ok\n")
    }
}

/** Fails on Compose or Material 3 use where the Architecture forbids it. */
@CacheableTask
abstract class CheckImportsTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val base = projectDir.get().asFile
        val violations =
            sources.files.sorted().flatMap { file ->
                ImportRules.violations(
                    projectPath.get(),
                    file.relativeTo(base).invariantSeparatorsPath,
                    file.readText(),
                )
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Import rule violations (Architecture, ADR-19, ADR-20):\n" + violations.joinToString("\n") {
                    "  $it"
                },
            )
        }
        report.get().asFile.writeText("ok\n")
    }
}

/** Fails when a `.kt` / `.kts` file doesn't start with the SPDX header. */
@CacheableTask
abstract class CheckLicenseHeadersTask : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val base = rootDir.get().asFile
        val missing = sources.files.filterNot {
            LicenseHeader.isValid(it.readText())
        }.map { it.relativeTo(base).invariantSeparatorsPath }.sorted()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These files don't start with the license header:\n" + missing.joinToString("\n") { "  $it" } +
                    "\nEvery .kt / .kts file must start with:\n" + LicenseHeader.EXPECTED,
            )
        }
        report.get().asFile.writeText("ok\n")
    }
}

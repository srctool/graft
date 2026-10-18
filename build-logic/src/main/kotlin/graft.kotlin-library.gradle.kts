// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Plain Kotlin/JVM module: core-*, provider-*, app-cli.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("graft.spotless")
    id("graft.checks")
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "com.srctool.graft"

// Leaf names repeat across layers (`:core:provider` vs the `:provider` folder) and say little alone (`git.jar`),
// so artifacts carry the full path: `graft-core-git.jar`.
base.archivesName = "graft" + project.path.replace(':', '-')

kotlin {
    jvmToolchain(21)
    // Public APIs in core-* are contracts for every other module, so they must be declared on purpose.
    if (project.path.startsWith(":core:")) explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

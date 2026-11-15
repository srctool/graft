// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Plain Kotlin/JVM module: :core:* (except :core:ui), :data:*, :domain, :presentation, :app:cli.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("graft.spotless")
    id("graft.checks")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Leaf names repeat across layers (`:core:git` and `:data:git`), and two projects with the same group and name
// collide in dependency resolution, so the group carries the parent path: `com.srctool.graft.core`.
group = (listOf("com.srctool.graft") + project.path.removePrefix(":").split(":").dropLast(1)).joinToString(".")

// Leaf names repeat across layers (`:core:provider` vs the `:provider` folder) and say little alone (`git.jar`),
// so artifacts carry the full path: `graft-core-git.jar`.
base.archivesName = "graft" + project.path.replace(':', '-')

kotlin {
    jvmToolchain(21)
    // Public APIs in :core:* and :data:* are contracts for every other module, so they must be declared on purpose.
    if (project.path.startsWith(":core:") || project.path.startsWith(":data:")) explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// CI's git-min job runs the tests against Git 2.38 with -Pgit=/opt/git-2.38/bin/git; tests read it from here.
val testGit = providers.gradleProperty("git")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testGit.orNull?.let { systemProperty("graft.test.git", it) }
}

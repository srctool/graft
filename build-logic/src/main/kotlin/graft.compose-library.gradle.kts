// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// Compose Desktop library: :module:core:ui, :module:feature:*, :module:navigation.

plugins {
    id("graft.kotlin-library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("compose-runtime").get())
    implementation(libs.findLibrary("compose-foundation").get())
    implementation(libs.findLibrary("compose-ui").get())
}

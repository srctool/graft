// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    `kotlin-dsl`
}

// Compile the convention plugins and run their tests on the project's JDK, not on whichever JDK started Gradle;
// otherwise a cached build from a newer JDK fails here with UnsupportedClassVersionError.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

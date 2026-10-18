// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// The root project: formatting for root scripts and build-logic, the license header check, and build-logic's own
// tests as part of `./gradlew check`.

plugins {
    id("graft.spotless")
    id("graft.license-headers")
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

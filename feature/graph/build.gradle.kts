// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

plugins {
    id("graft.compose-library")
}

dependencies {
    implementation(projects.core.repo)
    implementation(projects.core.data)
    implementation(projects.ui.kit)
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

// The third-party license report About shows (S40): every runtime dependency with its version and license, as JSON on
// the app's classpath at graft/licenses/third-party.json. Bundled assets (fonts, icons, templates) are in NOTICE.

import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.JsonReportRenderer

plugins {
    id("com.github.jk1.dependency-license-report")
}

licenseReport {
    outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.path
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf(LicenseBundleNormalizer())
    renderers = arrayOf(JsonReportRenderer("third-party.json", false))
}

// The plugin's task reads the Project while it runs, which the configuration cache can't store, so builds that include
// it (the app's `check` and `run`) configure again. The report becomes a resource of the app, so what ships always
// carries its own list.
val licenseResources =
    tasks.register<Copy>("licenseResources") {
        dependsOn(tasks.named("generateLicenseReport"))
        from(layout.buildDirectory.file("reports/licenses/third-party.json"))
        into(layout.buildDirectory.dir("generated/licenses/graft/licenses"))
    }

extensions.getByType<SourceSetContainer>().named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/licenses"))
}
tasks.named("processResources") { dependsOn(licenseResources) }

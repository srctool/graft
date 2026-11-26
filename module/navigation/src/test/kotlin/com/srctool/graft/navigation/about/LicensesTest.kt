// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.navigation.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicensesTest {
    private val report =
        """
        {"dependencies":[
          {"moduleName":"io.ktor:ktor-client-core","moduleVersion":"3.0.0",
           "moduleLicenses":[{"moduleLicense":"Apache License, Version 2.0","moduleLicenseUrl":"https://www.apache.org/licenses/LICENSE-2.0"}]},
          {"moduleName":"com.srctool.graft:data-git","moduleVersion":"0.1.0","moduleLicenses":[]},
          {"moduleName":"example:no-license","moduleVersion":"1"},
          {"moduleName":"io.ktor:ktor-client-core","moduleVersion":"3.0.0"}
        ]}
        """.trimIndent()

    @Test
    fun `the report's components are listed with version and license, Graft's own modules left out`() {
        val parsed = BundledLicenseSource.parse(report)
        assertEquals(
            listOf(
                LicenseEntry(
                    "io.ktor:ktor-client-core",
                    "3.0.0",
                    "Apache License, Version 2.0",
                    "https://www.apache.org/licenses/LICENSE-2.0",
                ),
                LicenseEntry("example:no-license", "1", "", null),
            ),
            parsed,
        )
    }

    @Test
    fun `a missing or unreadable report leaves just the bundled assets`() {
        assertEquals(emptyList(), BundledLicenseSource.parse(null))
        assertEquals(emptyList(), BundledLicenseSource.parse("not json"))
        assertEquals(emptyList(), BundledLicenseSource.parse("""{"other":1}"""))
        val names = BundledLicenseSource { null }.load().map { it.name }
        assertTrue("Inter" in names && "JetBrains Mono" in names)
    }

    @Test
    fun `the list is sorted by name, ignoring case`() {
        val names = BundledLicenseSource { report }.load().map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }
}

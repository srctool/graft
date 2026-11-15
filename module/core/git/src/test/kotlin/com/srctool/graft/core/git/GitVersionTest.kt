// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitVersionTest {
    @Test
    fun `reads the version strings of each platform`() {
        assertEquals(GitVersion(2, 45, 1), GitVersion.parse("git version 2.45.1\n"))
        assertEquals(GitVersion(2, 39, 3), GitVersion.parse("git version 2.39.3 (Apple Git-146)"))
        assertEquals(GitVersion(2, 47, 0), GitVersion.parse("git version 2.47.0.windows.1"))
        assertEquals(GitVersion(2, 38, 0), GitVersion.parse("git version 2.38.0.rc2"))
        assertEquals(GitVersion(3, 0, 0), GitVersion.parse("git version 3.0"))
    }

    @Test
    fun `rejects anything else`() {
        assertNull(GitVersion.parse("hub version 2.14.2"))
        assertNull(GitVersion.parse(""))
        assertNull(GitVersion.parse("git version x.y"))
    }

    @Test
    fun `orders by major, minor, patch`() {
        assertTrue(GitVersion(2, 37, 9) < GitVersion.MINIMUM)
        assertTrue(GitVersion(2, 38, 0) >= GitVersion.MINIMUM)
        assertTrue(GitVersion(2, 100, 0) > GitVersion(2, 9, 9))
        assertTrue(GitVersion(3, 0, 0) > GitVersion(2, 99, 99))
    }
}

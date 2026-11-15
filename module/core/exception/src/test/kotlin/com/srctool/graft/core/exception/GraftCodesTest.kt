// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.exception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraftCodesTest {
    // Sinew's built-in type codes (Error Model note); Graft's own types must never reuse one.
    private val sinewTypes =
        setOf(
            "NIC", "RTO", "VAL", "UNA", "FOR", "NTF", "CFL", "RTL", "SRV", "SUN",
            "AE", "UER", "DF", "LSC", "SF", "SR", "SW", "PTO", "GEN", "CNL",
        )

    @Test
    fun `Graft's types are unique and don't clash with Sinew's`() {
        val types = GraftCodes.Types.all
        assertEquals(types.size, types.toSet().size)
        assertEquals(emptySet(), types.toSet() intersect sinewTypes)
    }

    @Test
    fun `modules and types are short upper-case codes`() {
        (GraftCodes.MODULES + GraftCodes.Types.all).forEach { assertTrue(Regex("[A-Z]{2,4}").matches(it), it) }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.feature.workingcopy.domain.implementation.usecases

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** What [file] holds now, to tell later whether it was edited; null when it isn't a readable file. */
internal fun fingerprint(file: Path): String? = try {
    if (!Files.isRegularFile(file)) {
        null
    } else {
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    }
} catch (_: IOException) {
    null
}

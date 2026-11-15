// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

import java.nio.file.Path
import java.nio.file.Paths

/** A git config value and where git read it from (`--show-origin`: `file:/home/ann/.gitconfig`). */
public data class ConfigValue(val value: String, val origin: String) {
    /** The config file the value came from; null when it didn't come from a file. */
    val file: Path? get() = origin.takeIf { it.startsWith(FILE) }?.let { Paths.get(it.removePrefix(FILE)) }

    private companion object {
        const val FILE = "file:"
    }
}

/** `user.name` and `user.email` in the global git config (and the files it includes); null when unset. */
public data class GlobalIdentity(val name: ConfigValue?, val email: ConfigValue?)

/**
 * What [IdentityRepository.write][com.srctool.graft.data.git.api.repositories.IdentityRepository.write] did: the keys
 * it wrote, and the identity read back afterwards. When an included file sets the same key later, the value read back
 * is still that file's, and the UI says so.
 */
public data class IdentityWrite(val written: List<String>, val identity: GlobalIdentity)

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.configuration.instance

import kotlinx.serialization.Serializable

/** One request to the running app: a line of JSON. [token] proves the sender could read the user-only token file. */
@Serializable
public data class InstanceRequest(val token: String, val command: String, val paths: List<String> = emptyList()) {
    // The token never reaches a log through a request that gets printed.
    override fun toString(): String = "InstanceRequest(command=$command, paths=$paths)"

    public companion object {
        /** Asks whether an instance answers; changes nothing. */
        public const val PING: String = "ping"

        /** Opens each of the paths as a tab (none: only raises the window). */
        public const val OPEN: String = "open"
    }
}

/** The app's one-line answer. */
@Serializable
public data class InstanceResponse(val ok: Boolean, val error: String? = null)

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.exception

/**
 * The registry of Graft's parts of an error code (Conventions §3): `MODULE-LAYER-FUNCTION-TYPE`, for example
 * `GIT-R-PUSH-REJ`. Layers are Sinew's ([com.srctool.sinew.models.ExceptionLayer]); modules, functions and Graft's own
 * types are listed here so a test can check them for format and clashes.
 */
public object GraftCodes {
    /** Modules from the Conventions note. */
    public val MODULES: Set<String> = setOf("GIT", "REPO", "PRV", "NET", "DATA", "UI", "CLI", "SSH", "PRF")

    /** Graft's own failure types. HTTP failures use Sinew's built-in types instead (`NIC`, `RTO`, `UNA`, …). */
    public object Types {
        public const val REJECTED: String = "REJ"
        public const val AUTH: String = "AUTH"
        public const val CONFLICT: String = "CONF"
        public const val LOCKED: String = "LOCK"
        public const val NOT_FOUND: String = "NF"
        public const val NETWORK: String = "NET"
        public const val TIMEOUT: String = "TO"
        public const val VERSION: String = "VER"
        public const val EXIT: String = "EXIT"

        public val all: List<String> =
            listOf(REJECTED, AUTH, CONFLICT, LOCKED, NOT_FOUND, NETWORK, TIMEOUT, VERSION, EXIT)
    }

    private val functionPattern = Regex("[A-Z][A-Z0-9]{1,3}")

    /** Function codes are 2–4 upper-case letters or digits, as in Sinew: `PUSH`, `LOG`, `CLON`. */
    public fun requireFunction(function: String): String {
        require(functionPattern.matches(function)) {
            "Error function must be 2–4 upper-case letters or digits, got \"$function\""
        }
        return function
    }
}

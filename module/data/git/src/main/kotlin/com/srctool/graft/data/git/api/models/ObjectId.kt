// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** A git object name: 40 hex digits (SHA-1) or 64 (SHA-256), lower case. */
@JvmInline
public value class ObjectId(public val hex: String) {
    init {
        require((hex.length == 40 || hex.length == 64) && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Not a git object name: \"$hex\""
        }
    }

    /** The first [length] digits, as git abbreviates. */
    public fun short(length: Int = 7): String = hex.take(length)

    override fun toString(): String = hex
}

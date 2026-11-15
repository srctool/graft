// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/**
 * Which refs a log walks: the graph's hide and solo choices (Commit Graph, "Left panel"). Refs are full names
 * (`refs/heads/main`) or `HEAD`; anything else is refused, so a name can never reach git as an option.
 */
public sealed interface LogScope {
    /** Every branch, remote branch and tag, except the stash, Graft's own refs, and [hidden]. */
    public data class All(val hidden: Set<String> = emptySet()) : LogScope {
        init {
            hidden.forEach(::requireRefName)
        }
    }

    /** Only the history of [refs] (solo). */
    public data class Only(val refs: Set<String>) : LogScope {
        init {
            require(refs.isNotEmpty()) { "a solo scope needs at least one ref" }
            refs.forEach(::requireRefName)
        }
    }
}

private fun requireRefName(name: String) {
    require(name == "HEAD" || (name.startsWith("refs/") && name.length > "refs/".length)) {
        "Not a full ref name: \"$name\""
    }
}

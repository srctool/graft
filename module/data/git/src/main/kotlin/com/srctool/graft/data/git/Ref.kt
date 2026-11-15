// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

/** A git reference: a branch, remote branch, tag, the stash, or one of Graft's own refs. */
public data class Ref(
    /** The full name, for example `refs/heads/main`. */
    val name: String,
    /** What the ref points to directly: a commit, or the tag object of an annotated tag. */
    val target: ObjectId,
    /** For an annotated tag, the commit it tags; null otherwise. */
    val peeled: ObjectId? = null,
    /** True for the branch HEAD is on. */
    val isHead: Boolean = false,
    /** The branch's upstream, for example `refs/remotes/origin/main`, if one is configured. */
    val upstream: String? = null,
    /** Commits ahead of and behind the upstream; null when there's no upstream or it's gone. */
    val ahead: Int? = null,
    val behind: Int? = null,
    /** True when the upstream is configured but no longer exists. */
    val upstreamGone: Boolean = false,
    /** For a symbolic ref such as `refs/remotes/origin/HEAD`, the ref it points to. */
    val symbolicTarget: String? = null,
) {
    val kind: RefKind = RefKind.of(name)

    /** The name without its namespace: `main`, `origin/main`, `v1.0`, `shelves/0193…`. */
    val shortName: String get() = name.removePrefix(kind.prefix)

    /** The commit the ref leads to: the tagged commit for an annotated tag, the target otherwise. */
    val commit: ObjectId get() = peeled ?: target
}

public enum class RefKind(public val prefix: String) {
    LOCAL_BRANCH("refs/heads/"),
    REMOTE_BRANCH("refs/remotes/"),
    TAG("refs/tags/"),
    STASH("refs/"),
    GRAFT("refs/graft/"),
    OTHER("refs/"),
    ;

    public companion object {
        public fun of(name: String): RefKind = when {
            name.startsWith(LOCAL_BRANCH.prefix) -> LOCAL_BRANCH
            name.startsWith(REMOTE_BRANCH.prefix) -> REMOTE_BRANCH
            name.startsWith(TAG.prefix) -> TAG
            name == "refs/stash" -> STASH
            name.startsWith(GRAFT.prefix) -> GRAFT
            else -> OTHER
        }
    }
}

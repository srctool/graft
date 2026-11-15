// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/** Everything the commit details panel shows (Screen Inventory S5). */
public data class CommitDetails(
    val id: ObjectId,
    /** First parent first; empty for a root commit. */
    val parents: List<ObjectId>,
    val author: Signature,
    val committer: Signature,
    /** The whole message: subject, blank line, body, trailers. */
    val message: String,
    /** `Key: value` trailers at the end of the message (`Co-authored-by`, `Signed-off-by`), in order. */
    val trailers: List<Trailer>,
    val signature: SignatureStatus,
    /** What the commit changed, against its first parent (or nothing, for a root commit). */
    val files: List<ChangedFile>,
) {
    /** The message's first line. */
    val subject: String get() = message.substringBefore('\n')
}

/** Who and when: [time] in seconds since the epoch, [offsetMinutes] the time zone git recorded. */
public data class Signature(val name: String, val email: String, val time: Long, val offsetMinutes: Int)

public data class Trailer(val key: String, val value: String)

/** Git's `%G?`: whether the commit is signed, and what checking it found. */
public enum class SignatureStatus {
    /** Not signed. */
    NONE,
    GOOD,

    /** Good, but the key's validity is unknown. */
    GOOD_UNKNOWN_VALIDITY,
    EXPIRED_SIGNATURE,
    EXPIRED_KEY,
    REVOKED_KEY,
    BAD,

    /** Signed, but it couldn't be checked (no public key, no gpg). */
    CANNOT_CHECK,
}

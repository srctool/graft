// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.exception

/**
 * Removes secrets from text before it's stored in an error or a log (Conventions §4, ADR-7): provider tokens,
 * `Authorization` / `Cookie` values and passwords in URLs. Applied at write time, so nothing downstream has to.
 */
public object Redaction {
    public const val MASK: String = "****"

    private val rules: List<Pair<Regex, String>> =
        listOf(
            // https://user:secret@host and https://token@host
            Regex("""(\b[a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s@]+@""") to "$1$MASK@",
            Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)(\s*[:=]\s*)[^\r\n]+""") to "$1$2$MASK",
            Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 $MASK",
            Regex("""\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}""") to MASK,
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""") to MASK,
            Regex("""\bglpat-[A-Za-z0-9_-]{20,}""") to MASK,
            Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}""") to MASK,
            Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}""") to MASK,
        )

    public fun redact(text: String): String = rules.fold(text) { acc, (regex, replacement) ->
        regex.replace(acc, replacement)
    }
}

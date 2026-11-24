// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import java.security.MessageDigest
import kotlin.math.ln

/** What the secret check found in one line, before it becomes a [GuardFinding]. The real value never leaves here. */
internal class SecretHit(val label: String, val masked: String, val value: String)

/** The bundled rules (Commit Guards, "Checks"): local regexes and file patterns; nothing is sent anywhere. */
internal object GuardRules {
    private class Known(val label: String, val shown: String, val regex: Regex)

    // Order matters where formats overlap: `sk-ant-` before the general `sk-`.
    private val known =
        listOf(
            Known("GitHub token", "gh", Regex("""\b(gh[pousr]_)[A-Za-z0-9]{36,}""")),
            Known("GitHub token", "github_pat_", Regex("""\b(github_pat_)[A-Za-z0-9_]{50,}""")),
            Known("GitLab token", "glpat-", Regex("""\b(glpat-)[A-Za-z0-9_\-]{20,}""")),
            Known("AWS access key", "AKIA", Regex("""\b((?:AKIA|ASIA))[0-9A-Z]{16}\b""")),
            Known("Anthropic key", "sk-ant-", Regex("""\b(sk-ant-)[A-Za-z0-9_\-]{20,}""")),
            Known("API key", "sk-", Regex("""\b(sk-(?:proj-)?)[A-Za-z0-9_\-]{32,}""")),
            Known("Slack token", "xox", Regex("""\b(xox[bp]-)[A-Za-z0-9\-]{10,}""")),
            Known("Stripe key", "sk_live_", Regex("""\b([sr]k_live_)[A-Za-z0-9]{16,}""")),
            Known("Google API key", "AIza", Regex("""\b(AIza)[0-9A-Za-z_\-]{35}""")),
        )

    private val privateKey = Regex("""-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----""")

    // `password = "…"`, `token: …`, `API_SECRET=…`: the name, then the value.
    private val assignment =
        Regex(
            """(?i)\b([a-z0-9_.\-]*(?:password|passwd|secret|token|api[_\-]?key)[a-z0-9_.\-]*)""" +
                """\s*[:=]\s*["']?([^\s"',;]{12,})""",
        )

    private const val MIN_ENTROPY = 3.5

    /** The secrets in one added line. A value is reported once even if two rules see it. */
    fun secrets(line: String): List<SecretHit> {
        val hits = ArrayList<SecretHit>()
        val claimed = ArrayList<IntRange>()
        privateKey.find(line)?.let {
            // The header is not the secret (the key is on the lines after it) and is fine to show.
            hits += SecretHit("Private key", it.value, it.value)
            claimed += it.range
        }
        for (rule in known) {
            for (match in rule.regex.findAll(line)) {
                if (claimed.any { it.first <= match.range.last && match.range.first <= it.last }) continue
                claimed += match.range
                val prefix = match.groupValues[1]
                hits += SecretHit(rule.label, mask(prefix, match.value), match.value)
            }
        }
        for (match in assignment.findAll(line)) {
            val valueRange = match.groups[2]!!.range
            if (claimed.any { it.first <= valueRange.last && valueRange.first <= it.last }) continue
            val value = match.groupValues[2]
            if (!looksSecret(value)) continue
            claimed += valueRange
            hits += SecretHit("Secret", mask("${match.groupValues[1]} = ", value), value)
        }
        return hits
    }

    /** `ghp_****…a91F`: what the value starts with and its last four characters, never the middle. */
    fun mask(prefix: String, value: String): String = "$prefix****…${value.takeLast(LAST)}"

    private const val LAST = 4

    private fun looksSecret(value: String): Boolean {
        if (value.startsWith("\${") || value.startsWith("<") || value.startsWith("$") || value.startsWith("%")) {
            return false
        }
        val lower = value.lowercase()
        if (PLACEHOLDERS.any { it in lower }) return false
        if (value.toSet().size < MIN_DISTINCT) return false
        return entropy(value) >= MIN_ENTROPY
    }

    private val PLACEHOLDERS = listOf("example", "changeme", "placeholder", "your", "xxxx", "dummy", "redacted")
    private const val MIN_DISTINCT = 6

    /** Shannon entropy in bits per character. */
    fun entropy(text: String): Double {
        if (text.isEmpty()) return 0.0
        val counts = text.groupingBy { it }.eachCount()
        return -counts.values.sumOf { n ->
            val p = n.toDouble() / text.length
            p * ln(p) / ln(2.0)
        }
    }

    /** A line with `<<<<<<<` or `>>>>>>>` at its start, as git writes them. */
    fun isConflictMarker(line: String): Boolean =
        (line.startsWith("<<<<<<<") || line.startsWith(">>>>>>>")) && (line.length == 7 || line[7] == ' ')

    /** How [path]'s name is sensitive, or null. The second is whether it only warns. */
    fun sensitiveName(path: String): Pair<String, GuardSeverity>? {
        val name = path.substringAfterLast('/')
        val lower = name.lowercase()
        return when {
            lower == ".env.example" -> null
            lower == ".env" || lower.startsWith(".env.") -> ".env file" to GuardSeverity.BLOCK
            lower == "id_rsa" || lower == "id_ed25519" -> "Private key file" to GuardSeverity.BLOCK
            lower.endsWith(".pem") || lower.endsWith(".p12") -> "Key or certificate file" to GuardSeverity.BLOCK
            lower.endsWith(".keystore") || lower.endsWith(".jks") -> "Keystore" to GuardSeverity.BLOCK
            lower == "credentials.json" -> "Credentials file" to GuardSeverity.BLOCK
            lower == "google-services.json" -> "Google services file" to GuardSeverity.WARN
            else -> null
        }
    }

    /**
     * A finding's fingerprint: the rule, the path and a hash of what matched. It doesn't change when the line moves,
     * and a hash of 64 bits can't give the value back.
     */
    fun fingerprint(rule: GuardRule, path: String, what: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update("${rule.name}\u0000$path\u0000$what".toByteArray(Charsets.UTF_8))
        return sha.digest().take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val FINGERPRINT_BYTES = 8

    fun finding(
        rule: GuardRule,
        severity: GuardSeverity,
        path: String,
        line: Int?,
        excerpt: String,
        label: String,
        what: String,
    ) = GuardFinding(rule, severity, path, line, excerpt, label, fingerprint(rule, path, what))
}

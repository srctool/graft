// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.ui.l10n

import com.srctool.sinew.exception.Localizer
import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties

/**
 * Graft's own texts in English and Indonesian (Settings Reference, "Language"), for Sinew's localizer (ADR-23):
 * `rememberSinewLocalizer(GraftLocalizer(locale))` asks Graft first, then Sinew's own strings, then the message's
 * English fallback.
 *
 * Texts are `graft/l10n/graft.properties` (English) and `graft_<language>.properties`, read as UTF-8 rather than
 * through Compose resources, which generate code. `{name}` in a text is replaced by that argument.
 */
public class GraftLocalizer(locale: Locale = Locale.getDefault()) : Localizer {
    private val texts: Map<String, String> = load("graft/l10n/graft.properties") + load(languageFile(locale))

    override fun resolve(key: String, args: Map<String, Any>): String? = texts[key]?.let { format(it, args) }

    /** Every key Graft has a text for. */
    public val keys: Set<String> get() = texts.keys

    public companion object {
        private val placeholder = Regex("""\{(\w+)}""")

        /** The languages Graft has texts for, besides English. */
        public val languages: Set<String> = setOf("id")

        /** [text] with each `{name}` replaced by [args]`[name]`; a placeholder with no argument stays as it is. */
        public fun format(text: String, args: Map<String, Any>): String =
            placeholder.replace(text) { m -> args[m.groupValues[1]]?.toString() ?: m.value }

        internal fun languageFile(locale: Locale): String? =
            locale.language.takeIf { it in languages }?.let { "graft/l10n/graft_$it.properties" }

        internal fun load(resource: String?): Map<String, String> {
            if (resource == null) return emptyMap()
            val stream = GraftLocalizer::class.java.classLoader.getResourceAsStream(resource) ?: return emptyMap()
            val properties = Properties()
            InputStreamReader(stream, Charsets.UTF_8).use(properties::load)
            return properties.stringPropertyNames().associateWith(properties::getProperty)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Decodes text git printed. Git stores names and messages as bytes: modern commits are UTF-8, but old ones (and ones
 * written without an `encoding` header) may be Latin-1 or Windows-1252. Invalid UTF-8 is read as Windows-1252, which
 * maps every byte, so `Jos\xE9` shows as "José" rather than a replacement character.
 */
internal object GitText {
    private val windows1252 = charset("windows-1252")

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length == 0) return ""
        // Pure ASCII is the common case and needs no decoder.
        var ascii = true
        for (i in offset until offset + length) {
            if (bytes[i] < 0) {
                ascii = false
                break
            }
        }
        if (ascii) return String(bytes, offset, length, Charsets.ISO_8859_1)
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, offset, length, windows1252)
        }
    }
}

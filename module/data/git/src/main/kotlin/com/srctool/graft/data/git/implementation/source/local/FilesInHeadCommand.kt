// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.data.git.api.models.FileList

/** Every path in HEAD's tree; `-z` so a name with a space, a quote or a line break arrives whole. */
internal class FilesInHeadCommand(private val limit: Int) : GitCommand<FileList>() {
    override val function = "TREE"
    override val args = listOf("ls-tree", "-r", "-z", "--name-only", "HEAD")

    override fun parse(stdout: ByteArray): FileList {
        val paths = ArrayList<String>()
        var start = 0
        var truncated = false
        for (i in stdout.indices) {
            if (stdout[i] != 0.toByte()) continue
            if (paths.size >= limit) {
                truncated = true
                break
            }
            if (i > start) paths += GitText.decode(stdout.copyOfRange(start, i))
            start = i + 1
        }
        return FileList(paths, truncated)
    }
}

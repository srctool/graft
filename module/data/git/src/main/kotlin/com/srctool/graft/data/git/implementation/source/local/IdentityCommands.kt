// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.core.git.GitCommand
import com.srctool.graft.core.git.GitParseException
import com.srctool.graft.data.git.api.models.ConfigValue

/** One global value with its origin, following includes; exit 1 when it isn't set. */
internal class GlobalConfigGetCommand(key: String) : GitCommand<ConfigValue?>() {
    override val function = "IDNT"
    override val args = listOf("config", "--global", "--includes", "--show-origin", "-z", "--get", key)
    override val successExitCodes = setOf(0, 1)

    override fun parse(stdout: ByteArray): ConfigValue? {
        if (stdout.isEmpty()) return null
        val parts = GitText.decode(stdout).removeSuffix("\u0000").split('\u0000')
        if (parts.size != 2) throw GitParseException("config origin and value: ${parts.size} fields")
        return ConfigValue(value = parts[1], origin = parts[0])
    }
}

/** Sets [key] in the global config file, replacing every value it has there. */
internal class GlobalConfigSetCommand(key: String, value: String) : GitCommand<Unit>() {
    init {
        // git config takes the value positionally and reads "--" as the value itself: "-x" would be an option.
        require(!value.startsWith('-')) { "a config value can't start with '-'" }
    }

    override val function = "IDNT"
    override val args = listOf("config", "--global", "--replace-all", key, value)

    override fun parse(stdout: ByteArray) {}
}

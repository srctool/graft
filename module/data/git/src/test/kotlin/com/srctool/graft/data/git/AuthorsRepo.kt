// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.data.git.api.models.AuthorType
import java.nio.file.Files
import java.nio.file.Path

/** The `authors` fixture repository: one commit per author type the graph's filter tells apart (G1 Task 5). */
object AuthorsRepo {
    /** Each commit's subject and the type the parser must read. */
    val expected: Map<String, AuthorType> =
        linkedMapOf(
            "by a person" to AuthorType.PERSON,
            "with a human co-author" to AuthorType.PERSON,
            "with Claude" to AuthorType.AI_ASSISTED,
            "with Cursor" to AuthorType.AI_ASSISTED,
            "with Copilot" to AuthorType.AI_ASSISTED,
            "lowercase trailer key" to AuthorType.AI_ASSISTED,
            "by dependabot" to AuthorType.BOT,
            "by github-actions" to AuthorType.BOT,
        )

    fun build(sandbox: GitSandbox): Path {
        val repo = sandbox.repo("authors")
        var n = 0
        fun commit(message: String, vararg extra: String) {
            Files.writeString(repo.resolve("f.txt"), "${n++}")
            sandbox.git(repo, "add", "f.txt")
            sandbox.git(repo, "commit", "--quiet", *extra, "-m", message)
        }
        commit("by a person")
        commit("with a human co-author\n\nCo-authored-by: Ann Lee <ann@example.invalid>")
        commit("with Claude\n\nCo-authored-by: Claude Opus 5.5 <noreply@anthropic.com>")
        commit("with Cursor\n\nCo-authored-by: Cursor Agent <cursoragent@cursor.com>")
        commit("with Copilot\n\nCo-authored-by: Copilot <175728472+Copilot@users.noreply.github.com>")
        commit("lowercase trailer key\n\nco-authored-by: Claude <noreply@anthropic.com>")
        commit("by dependabot", "--author=dependabot[bot] <49699333+dependabot[bot]@users.noreply.github.com>")
        commit(
            "by github-actions",
            "--author=github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>",
        )
        return repo
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git

import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.core.git.testing.RawGitCommand
import com.srctool.sinew.models.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `git apply` failures get their own error (`GIT-R-APLY-PAT`), with git's words kept. */
@ExtendWith(GitSandboxExtension::class)
class PatchFailureTest {
    private val rejected = "error: patch failed: a.txt:1\nerror: a.txt: patch does not apply"

    @Test
    fun `the three ways a patch is refused are told apart`() {
        assertEquals(PatchProblem.DOES_NOT_APPLY, patchFailure("APLY", listOf("apply"), 1, rejected)?.problem)
        assertEquals(
            PatchProblem.DOES_NOT_APPLY,
            patchFailure("APLY", listOf("apply"), 1, "error: a.txt: does not match index")?.problem,
        )
        assertEquals(
            PatchProblem.ALREADY_EXISTS,
            patchFailure("APLY", listOf("apply"), 1, "error: new.txt: already exists in working directory")?.problem,
        )
        assertEquals(
            PatchProblem.CORRUPT,
            patchFailure("APLY", listOf("apply"), 1, "error: corrupt patch at line 7")?.problem,
        )
    }

    @Test
    fun `other failures are not patch failures`() {
        assertNull(patchFailure("APLY", listOf("apply"), 1, "fatal: not a git repository"))
        assertNull(patchFailure("APLY", listOf("apply"), 1, ""))
    }

    @Test
    fun `the code and message follow the conventions, and git's output is kept`() {
        val e = patchFailure("APLY", listOf("apply", "--cached"), 1, rejected)!!
        assertEquals("GIT-R-APLY-PAT", e.code)
        assertEquals(1, e.exitCode)
        assertTrue(e.stderr.contains("patch does not apply"))
    }

    @Test
    fun `a real git apply that cannot apply comes back as a patch failure`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "one\ntwo\nthree\n") } }
        // The file moved on after the patch was made, so its context no longer matches.
        Files.writeString(repo.resolve("a.txt"), "ONE\ntwo\nthree\n")
        val patch =
            """
            |diff --git a/a.txt b/a.txt
            |--- a/a.txt
            |+++ b/a.txt
            |@@ -1,3 +1,3 @@
            | one
            |-two
            |+2
            | three
            |
            """.trimMargin().toByteArray()
        val result =
            sandbox.runner().run(RawGitCommand(listOf("apply"), function = "APLY"), repo, input = patch)
        assertIs<Result.Failure>(result)
        val error = assertIs<GitPatchException>(result.error)
        assertEquals("GIT-R-APLY-PAT", error.code)
    }
}

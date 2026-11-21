// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.LineRange
import com.srctool.graft.data.git.implementation.source.local.ApplyTo
import com.srctool.graft.data.git.implementation.source.local.PatchBuilder
import com.srctool.graft.data.git.implementation.source.local.RawLine
import com.srctool.graft.data.git.implementation.source.local.RawPatchParser
import com.srctool.graft.data.git.implementation.source.local.SelectionOutOfRange
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Patches built from a selection, pure: bytes in, bytes out, so the recount rules can be read off the strings. */
class PatchBuilderTest {
    /** Three hunks that each add one line, so every later hunk sits one line lower in the new file. */
    private val threeHunks =
        """
        |diff --git a/f b/f
        |index 1111111..2222222 100644
        |--- a/f
        |+++ b/f
        |@@ -2,3 +2,4 @@ fun a
        | b
        |+X
        | c
        | d
        |@@ -10,3 +11,4 @@ fun b
        | j
        |+Y
        | k
        | l
        |@@ -20,3 +22,4 @@
        | t
        |+Z
        | u
        | v
        |
        """.trimMargin()

    private fun file(text: String) = RawPatchParser.parse(text.toByteArray(Charsets.ISO_8859_1)).single()

    private fun build(patch: String, scope: Scope, applyTo: ApplyTo, path: String = "f"): String? =
        PatchBuilder.build(path, file(patch), scope, applyTo)?.toString(Charsets.ISO_8859_1)

    private fun headers(patch: String?) = patch!!.lines().filter { it.startsWith("@@") }

    @Test
    fun `the parser keeps a line's exact bytes`() {
        val raw = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n a\r\n-bé\n+Bé\r\n"
        val hunk = file(raw).hunks.single()
        assertContentEquals("a\r".toByteArray(Charsets.ISO_8859_1), hunk.lines[0].content)
        assertContentEquals("bé".toByteArray(Charsets.ISO_8859_1), hunk.lines[1].content)
        assertEquals(listOf(RawLine.Kind.CONTEXT, RawLine.Kind.REMOVED, RawLine.Kind.ADDED), hunk.lines.map { it.kind })
    }

    @Test
    fun `staging one hunk keeps its start line because nothing above it was added`() {
        val patch = build(threeHunks, Scope.Hunks(setOf(1)), ApplyTo.OLD)
        assertEquals(listOf("@@ -10,3 +10,4 @@ fun b"), headers(patch))
    }

    @Test
    fun `staging hunks around a skipped one moves the later hunk by what was added above it`() {
        val patch = build(threeHunks, Scope.Hunks(setOf(0, 2)), ApplyTo.OLD)
        assertEquals(listOf("@@ -2,3 +2,4 @@ fun a", "@@ -20,3 +21,4 @@"), headers(patch))
    }

    @Test
    fun `unstaging counts from the new side, which still has every hunk`() {
        val patch = build(threeHunks, Scope.Hunks(setOf(0, 2)), ApplyTo.NEW)
        assertEquals(listOf("@@ -2,3 +2,4 @@ fun a", "@@ -21,3 +22,4 @@"), headers(patch))
    }

    @Test
    fun `a patch names the file once, whatever the diff said`() {
        val patch = build(threeHunks, Scope.Hunks(setOf(0)), ApplyTo.OLD)!!
        assertEquals("diff --git a/f b/f\n--- a/f\n+++ b/f\n", patch.substringBefore("@@"))
    }

    private val twoAdds =
        """
        |diff --git a/f b/f
        |--- a/f
        |+++ b/f
        |@@ -1,3 +1,5 @@
        | a
        |+X1
        |+X2
        | b
        | c
        |
        """.trimMargin()

    @Test
    fun `an unselected addition is left out when staging and kept as context when unstaging`() {
        val second = Scope.Lines(listOf(LineRange(0, 2)))
        val staging = build(twoAdds, second, ApplyTo.OLD)!!
        assertEquals("@@ -1,3 +1,4 @@\n a\n+X2\n b\n c\n", staging.substringAfter("+++ b/f\n"))
        val unstaging = build(twoAdds, second, ApplyTo.NEW)!!
        assertEquals("@@ -1,4 +1,5 @@\n a\n X1\n+X2\n b\n c\n", unstaging.substringAfter("+++ b/f\n"))
    }

    private val replaced =
        """
        |diff --git a/f b/f
        |--- a/f
        |+++ b/f
        |@@ -1,4 +1,4 @@
        | a
        |-b
        |-c
        |+B
        |+C
        | d
        |
        """.trimMargin()

    @Test
    fun `an unselected removal stays as context when staging and is left out when unstaging`() {
        val onlyRemovingB = Scope.Lines(listOf(LineRange(0, 1)))
        assertEquals(
            "@@ -1,4 +1,3 @@\n a\n-b\n c\n d\n",
            build(replaced, onlyRemovingB, ApplyTo.OLD)!!.substringAfter("+++ b/f\n"),
        )
        // The new side holds B and C, so they are context; c was never there.
        assertEquals(
            "@@ -1,5 +1,4 @@\n a\n-b\n B\n C\n d\n",
            build(replaced, onlyRemovingB, ApplyTo.NEW)!!.substringAfter("+++ b/f\n"),
        )
    }

    @Test
    fun `selecting context lines selects nothing`() {
        assertNull(build(twoAdds, Scope.Lines(listOf(LineRange(0, 0, 0))), ApplyTo.OLD))
        assertNull(build(twoAdds, Scope.Hunks(emptySet()), ApplyTo.OLD))
    }

    @Test
    fun `a hunk or line the diff does not have is refused`() {
        assertFailsWith<SelectionOutOfRange> { build(twoAdds, Scope.Hunks(setOf(3)), ApplyTo.OLD) }
        assertFailsWith<SelectionOutOfRange> { build(twoAdds, Scope.Lines(listOf(LineRange(0, 1, 9))), ApplyTo.OLD) }
        assertFailsWith<SelectionOutOfRange> { build(twoAdds, Scope.Lines(listOf(LineRange(1, 0))), ApplyTo.OLD) }
    }

    private val endsWithoutNewline =
        """
        |diff --git a/f b/f
        |--- a/f
        |+++ b/f
        |@@ -1,5 +1,5 @@
        |-one
        |+ONE
        | 2
        | 3
        | 4
        |-end
        |\ No newline at end of file
        |+END
        |\ No newline at end of file
        |
        """.trimMargin()

    @Test
    fun `a change at a missing final newline is one unit`() {
        // Selecting only the removal selects the addition after it too, and both keep their markers.
        val patch = build(endsWithoutNewline, Scope.Lines(listOf(LineRange(0, 5, 5))), ApplyTo.OLD)!!
        assertEquals(
            "@@ -1,5 +1,5 @@\n one\n 2\n 3\n 4\n-end\n\\ No newline at end of file\n" +
                "+END\n\\ No newline at end of file\n",
            patch.substringAfter("+++ b/f\n"),
        )
    }

    @Test
    fun `a change elsewhere leaves the missing newline as it was`() {
        val patch = build(endsWithoutNewline, Scope.Lines(listOf(LineRange(0, 0, 1))), ApplyTo.OLD)!!
        assertEquals(
            "@@ -1,5 +1,5 @@\n-one\n+ONE\n 2\n 3\n 4\n end\n\\ No newline at end of file\n",
            patch.substringAfter("+++ b/f\n"),
        )
    }

    @Test
    fun `a new file is written as one`() {
        val patch =
            """
            |diff --git a/n b/n
            |new file mode 100755
            |index 0000000..1111111
            |--- /dev/null
            |+++ b/n
            |@@ -0,0 +1,3 @@
            |+a
            |+b
            |+c
            |
            """.trimMargin()
        val partial = build(patch, Scope.Lines(listOf(LineRange(0, 0, 1))), ApplyTo.OLD, "n")!!
        assertEquals(
            "diff --git a/n b/n\nnew file mode 100755\n--- /dev/null\n+++ b/n\n@@ -0,0 +1,2 @@\n+a\n+b\n",
            partial,
        )
        // Unstaging some of its lines leaves a file, so it is a change to one, not a creation.
        val unstaged = build(patch, Scope.Lines(listOf(LineRange(0, 0, 1))), ApplyTo.NEW, "n")!!
        assertEquals("diff --git a/n b/n\n--- a/n\n+++ b/n\n@@ -1 +1,3 @@\n+a\n+b\n c\n", unstaged)
    }

    @Test
    fun `a deleted file stays a deletion only when every line goes`() {
        val patch =
            """
            |diff --git a/g b/g
            |deleted file mode 100644
            |index 1111111..0000000
            |--- a/g
            |+++ /dev/null
            |@@ -1,3 +0,0 @@
            |-a
            |-b
            |-c
            |
            """.trimMargin()
        val all = build(patch, Scope.Hunks(setOf(0)), ApplyTo.OLD, "g")!!
        assertEquals(
            "diff --git a/g b/g\ndeleted file mode 100644\n--- a/g\n+++ /dev/null\n@@ -1,3 +0,0 @@\n-a\n-b\n-c\n",
            all,
        )
        val some = build(patch, Scope.Lines(listOf(LineRange(0, 0))), ApplyTo.OLD, "g")!!
        assertEquals("diff --git a/g b/g\n--- a/g\n+++ b/g\n@@ -1,3 +1,2 @@\n-a\n b\n c\n", some)
    }

    @Test
    fun `names with quotes, spaces and non-ASCII letters are written the way git reads them`() {
        val patch = file(twoAdds.replace("a/f", "a/x").replace("b/f", "b/x"))
        val spaced = PatchBuilder.build("my file.txt", patch, Scope.Hunks(setOf(0)), ApplyTo.OLD)!!
        assertEquals(
            "diff --git a/my file.txt b/my file.txt\n--- a/my file.txt\t\n+++ b/my file.txt\t\n",
            spaced.toString(Charsets.ISO_8859_1).substringBefore("@@"),
        )
        val odd = PatchBuilder.build("café \"q\".txt", patch, Scope.Hunks(setOf(0)), ApplyTo.OLD)!!
        assertEquals(
            "diff --git \"a/caf\\303\\251 \\\"q\\\".txt\" \"b/caf\\303\\251 \\\"q\\\".txt\"\n" +
                "--- \"a/caf\\303\\251 \\\"q\\\".txt\"\n+++ \"b/caf\\303\\251 \\\"q\\\".txt\"\n",
            odd.toString(Charsets.ISO_8859_1).substringBefore("@@"),
        )
    }

    @Test
    fun `a whole file is not a patch`() {
        assertFailsWith<IllegalArgumentException> { build(twoAdds, Scope.WholeFile, ApplyTo.OLD) }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git

import com.srctool.graft.core.git.GitGuardRefusedException
import com.srctool.graft.core.git.GuardProblem
import com.srctool.graft.core.git.testing.GitSandbox
import com.srctool.graft.core.git.testing.GitSandboxExtension
import com.srctool.graft.data.git.api.models.Allowances
import com.srctool.graft.data.git.api.models.ChangeSelection
import com.srctool.graft.data.git.api.models.FileSelection
import com.srctool.graft.data.git.api.models.FileSelection.Scope
import com.srctool.graft.data.git.api.models.GuardFinding
import com.srctool.graft.data.git.api.models.GuardOptions
import com.srctool.graft.data.git.api.models.GuardRule
import com.srctool.graft.data.git.api.models.GuardSeverity
import com.srctool.graft.data.git.api.models.GuardTarget
import com.srctool.graft.data.git.api.models.PathGlob
import com.srctool.graft.data.git.implementation.repositories.GitGuardRepository
import com.srctool.graft.data.git.implementation.repositories.GitLocalHistoryRepository
import com.srctool.graft.data.git.implementation.repositories.GitOperationLogRepository
import com.srctool.graft.data.git.implementation.repositories.GitStagingRepository
import com.srctool.graft.data.git.implementation.repositories.MutationRecorder
import com.srctool.graft.data.git.implementation.source.local.GuardDiffScanner
import com.srctool.graft.data.git.implementation.source.local.GuardRules
import com.srctool.graft.data.git.implementation.source.local.GuardToml
import com.srctool.graft.data.git.implementation.source.local.OpLogStore
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Commit Guards: every rule with positive and negative samples, masking, fingerprints, the allow-lists, and the three
 * scan sources on real repositories. Tokens are generated here, so no real-looking secret is in the source.
 */
@ExtendWith(GitSandboxExtension::class)
class GuardTest {
    private class Rig(sandbox: GitSandbox) {
        val runner = sandbox.runner()
        private val store = OpLogStore()
        val recorder = MutationRecorder(runner, store)
        val staging = GitStagingRepository(runner, GitLocalHistoryRepository(runner), recorder)
        val guard = GitGuardRepository(runner, staging, recorder)
        val log = GitOperationLogRepository(recorder, store)
    }

    private val random = Random(42)
    private val alnum = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    /** [n] random letters and digits: looks like a token, is nobody's. */
    private fun body(n: Int) = (1..n).map { alnum.random(random) }.joinToString("")

    private fun write(repo: Path, file: String, text: String) {
        val path = repo.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun stage(sandbox: GitSandbox, repo: Path, file: String, text: String) {
        write(repo, file, text)
        sandbox.git(repo, "add", "--", file)
    }

    private fun secretsIn(line: String) = GuardRules.secrets(line)

    // --- secrets -----------------------------------------------------------------------------------------------

    @Test
    fun `every known token format is detected`() {
        val samples =
            mapOf(
                "GitHub token" to listOf("ghp_" + body(36), "gho_" + body(40), "github_pat_" + body(60)),
                "GitLab token" to listOf("glpat-" + body(20)),
                "AWS access key" to listOf("AKIA" + body(16).uppercase().filter { it.isLetterOrDigit() }),
                "Anthropic key" to listOf("sk-ant-" + body(40)),
                "API key" to listOf("sk-" + body(48), "sk-proj-" + body(48)),
                "Slack token" to listOf("xoxb-" + body(30), "xoxp-" + body(30)),
                "Stripe key" to listOf("sk_live_" + body(24), "rk_live_" + body(24)),
                "Google API key" to listOf("AIza" + body(35)),
            )
        for ((label, tokens) in samples) {
            for (token in tokens) {
                val hits = secretsIn("const key = \"$token\";")
                assertEquals(listOf(label), hits.map { it.label }, "for $label")
                assertEquals(token, hits.single().value)
            }
        }
    }

    @Test
    fun `an AWS key needs exactly sixteen characters after its prefix`() {
        assertTrue(secretsIn("AKIA" + "A".repeat(15)).isEmpty())
        assertEquals(1, secretsIn("AKIA" + "A".repeat(16)).size)
    }

    @Test
    fun `private key headers are found, whatever the algorithm`() {
        for (kind in listOf("RSA ", "EC ", "OPENSSH ", "DSA ", "ENCRYPTED ", "")) {
            val hits = secretsIn("-----BEGIN ${kind}PRIVATE KEY-----")
            assertEquals(listOf("Private key"), hits.map { it.label })
        }
        assertTrue(secretsIn("-----BEGIN CERTIFICATE-----").isEmpty())
        assertTrue(secretsIn("-----BEGIN PUBLIC KEY-----").isEmpty())
    }

    @Test
    fun `a high-entropy value next to password, secret or token is a secret`() {
        for (name in listOf("password", "DB_PASSWORD", "client_secret", "auth_token", "api_key", "apiKey")) {
            val value = body(24)
            val hits = secretsIn("$name = \"$value\"")
            assertEquals(listOf("Secret"), hits.map { it.label }, name)
        }
        assertEquals(1, secretsIn("token: " + body(30)).size)
    }

    @Test
    fun `ordinary values next to those words are not secrets`() {
        val clean =
            listOf(
                "password = \"changeme-please\"",
                "password = \${DB_PASSWORD}",
                "password = <your-password-here>",
                "token = \"aaaaaaaaaaaaaaaaaaaa\"",
                "secret = \"example-secret-value\"",
                "tokenizer = \"whitespace-separated\"".replace("tokenizer", "name"),
                "val password: String = \"\"",
                "password = \"short\"",
                "passwordField.setText(\"hello world\")",
            )
        for (line in clean) assertTrue(secretsIn(line).isEmpty(), line)
    }

    @Test
    fun `a value is reported once even when two rules see it`() {
        val token = "ghp_" + body(36)
        assertEquals(1, secretsIn("github_token = \"$token\"").size)
    }

    @Test
    fun `masking keeps the start and the last four and never the middle`() {
        val token = "ghp_" + body(36)
        val hit = secretsIn("x = \"$token\"").single()
        assertEquals("ghp_****…${token.takeLast(4)}", hit.masked)
        assertFalse(hit.masked.contains(token.substring(4, 20)))
        val entropy = secretsIn("password = \"${"Zq7" + body(21)}\"").single()
        assertTrue(entropy.masked.startsWith("password = ****…"))
        val anthropic = "sk-ant-" + "A".repeat(30) + "wxyz"
        assertEquals("sk-ant-****…wxyz", secretsIn(anthropic).single().masked)
    }

    @Test
    fun `no model that leaves the scanner holds a secret value`() {
        val token = "ghp_" + body(36)
        val patch =
            """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -0,0 +1 @@
            +key = $token
            """.trimIndent() + "\n"
        val findings = GuardDiffScanner.scan(patch.byteInputStream(), secrets = true, markers = true).toList()
        assertEquals(1, findings.size)
        assertFalse(findings.toString().contains(token))
        assertFalse(findings.toString().contains(token.substring(8, 30)))
    }

    // --- the diff scanner ---------------------------------------------------------------------------------------

    private fun patch(vararg lines: String) = (lines.toList().joinToString("\n") + "\n").byteInputStream()

    @Test
    fun `only added lines are scanned and their line numbers are the new file's`() {
        val token = "ghp_" + body(36)
        val findings =
            GuardDiffScanner.scan(
                patch(
                    "diff --git a/a.txt b/a.txt",
                    "--- a/a.txt",
                    "+++ b/a.txt",
                    "@@ -3,2 +3,3 @@",
                    "-old = $token",
                    "+new line",
                    "+key = $token",
                    " context",
                    "@@ -20,0 +22,1 @@",
                    "+<<<<<<< HEAD",
                ),
                secrets = true,
                markers = true,
            ).toList()
        assertEquals(
            listOf(GuardRule.SECRET to 4, GuardRule.CONFLICT_MARKER to 22),
            findings.map { it.rule to it.line },
        )
    }

    @Test
    fun `a line that looks like a file header inside a hunk is content`() {
        val token = "ghp_" + body(36)
        val findings =
            GuardDiffScanner.scan(
                patch(
                    "diff --git a/a.txt b/a.txt",
                    "--- a/a.txt",
                    "+++ b/a.txt",
                    "@@ -0,0 +1,2 @@",
                    "+++ b/other.txt $token",
                    "+<<<<<<< HEAD",
                ),
                secrets = true,
                markers = true,
            ).toList()
        assertEquals(listOf("a.txt", "a.txt"), findings.map { it.path })
    }

    @Test
    fun `a binary file and a deletion are not read`() {
        val token = "ghp_" + body(36)
        val findings =
            GuardDiffScanner.scan(
                patch(
                    "diff --git a/pic.bin b/pic.bin",
                    "Binary files a/pic.bin and b/pic.bin differ",
                    "diff --git a/gone.txt b/gone.txt",
                    "--- a/gone.txt",
                    "+++ /dev/null",
                    "@@ -1 +0,0 @@",
                    "-key = $token",
                ),
                secrets = true,
                markers = true,
            ).toList()
        assertEquals(emptyList(), findings)
    }

    @Test
    fun `git's quoted names and the tab after a name with spaces are read`() {
        assertEquals("café.txt", GuardDiffScanner.unquote("\"caf\\303\\251.txt\""))
        assertEquals("a\"b\tc", GuardDiffScanner.unquote("\"a\\\"b\\tc\""))
        val token = "ghp_" + body(36)
        val findings =
            GuardDiffScanner.scan(
                patch(
                    "diff --git a/my file.txt b/my file.txt",
                    "--- a/my file.txt\t",
                    "+++ b/my file.txt\t",
                    "@@ -0,0 +1 @@",
                    "+k = $token",
                    "diff --git \"a/caf\\303\\251.txt\" \"b/caf\\303\\251.txt\"",
                    "--- \"a/caf\\303\\251.txt\"",
                    "+++ \"b/caf\\303\\251.txt\"",
                    "@@ -0,0 +1 @@",
                    "+k = $token",
                ),
                secrets = true,
                markers = false,
            ).toList()
        assertEquals(listOf("my file.txt", "café.txt"), findings.map { it.path })
    }

    @Test
    fun `conflict markers are found at the start of a line only`() {
        assertTrue(GuardRules.isConflictMarker("<<<<<<< HEAD"))
        assertTrue(GuardRules.isConflictMarker(">>>>>>> feature/x"))
        assertTrue(GuardRules.isConflictMarker("<<<<<<<"))
        assertFalse(GuardRules.isConflictMarker("<<<<<<<<"))
        assertFalse(GuardRules.isConflictMarker("  <<<<<<< HEAD"))
        assertFalse(GuardRules.isConflictMarker("a <<<<<<< b"))
        assertFalse(GuardRules.isConflictMarker("======="))
        assertFalse(GuardRules.isConflictMarker("<<< not enough"))
    }

    // --- sensitive files ---------------------------------------------------------------------------------------

    @Test
    fun `sensitive file names block, except the ones that are meant to be committed`() {
        val block =
            listOf(
                ".env", ".env.local", ".env.production", "app/.env", "id_rsa", "ssh/id_ed25519", "server.pem",
                "cert.p12", "release.keystore", "debug.jks", "credentials.json",
            )
        for (name in block) {
            assertEquals(GuardSeverity.BLOCK, GuardRules.sensitiveName(name)?.second, name)
        }
        assertEquals(GuardSeverity.WARN, GuardRules.sensitiveName("app/google-services.json")?.second)
        val fine =
            listOf(".env.example", "config/.env.example", "environment.kt", "id_rsa.pub", "readme.md", "keys.pem.txt")
        for (name in fine + "dotenv") {
            assertNull(GuardRules.sensitiveName(name), name)
        }
    }

    // --- fingerprints and globs --------------------------------------------------------------------------------

    @Test
    fun `a fingerprint survives a line move and tells values and files apart`() {
        val a = GuardRules.fingerprint(GuardRule.SECRET, "a.txt", "value-1")
        assertEquals(a, GuardRules.fingerprint(GuardRule.SECRET, "a.txt", "value-1"))
        assertNotEquals(a, GuardRules.fingerprint(GuardRule.SECRET, "a.txt", "value-2"))
        assertNotEquals(a, GuardRules.fingerprint(GuardRule.SECRET, "b.txt", "value-1"))
        assertNotEquals(a, GuardRules.fingerprint(GuardRule.CONFLICT_MARKER, "a.txt", "value-1"))
        assertEquals(16, a.length)
        assertFalse(a.contains("value"))
    }

    @Test
    fun `path patterns stay in a folder with a star and cross folders with two`() {
        assertTrue(PathGlob.matches("test/fixtures/**", "test/fixtures/a/b/key.txt"))
        assertTrue(PathGlob.matches("**/*.sample", "deep/er/key.sample"))
        assertTrue(PathGlob.matches("**/*.sample", "key.sample"))
        assertTrue(PathGlob.matches("*.pem", "server.pem"))
        assertFalse(PathGlob.matches("*.pem", "certs/server.pem"))
        assertTrue(PathGlob.matches("/certs/?.pem", "certs/a.pem"))
        assertFalse(PathGlob.matches("certs/?.pem", "certs/ab.pem"))
        assertFalse(PathGlob.matches("a.b", "aXb"))
    }

    // --- guard.toml ---------------------------------------------------------------------------------------------

    @Test
    fun `guard toml round trips and reads comments and multi-line arrays`() {
        val text =
            """
            # shared exceptions
            version = 1

            [allow]
            paths = [
              "test/fixtures/**",  # fake keys
              "docs/\"quoted\".md",
            ]
            fingerprints = ["0123456789abcdef"]
            """.trimIndent()
        val read = GuardToml.parse(text)
        assertFalse(read.readOnly)
        assertEquals(listOf("test/fixtures/**", "docs/\"quoted\".md"), read.value.pathPatterns)
        assertEquals(setOf("0123456789abcdef"), read.value.fingerprints)
        assertEquals(read.value, GuardToml.parse(GuardToml.render(read.value)).value)
    }

    @Test
    fun `a newer guard toml is read-only and a broken one is not rewritten`() {
        assertTrue(GuardToml.parse("version = 2\n[allow]\nfingerprints = [\"x\"]\n").readOnly)
        assertFailsParsing("[allow]\nfingerprints = [\"x\"]\n")
        assertFailsParsing("version = 1\n[allow]\npaths = [1, 2]\n")
        assertFailsParsing("version = 1\nnonsense\n")
    }

    private fun assertFailsParsing(text: String) {
        val threw = runCatching { GuardToml.parse(text) }.isFailure
        assertTrue(threw, text)
    }

    // --- a real repository -------------------------------------------------------------------------------------

    private fun GuardFinding.key() = "${rule.name}:$path:${line ?: "-"}"

    private suspend fun Rig.scan(
        repo: Path,
        target: GuardTarget = GuardTarget.Staged,
        options: GuardOptions = GuardOptions(),
    ) = guard.scan(repo, target, options).getOrThrow()

    @Test
    fun `staged secrets, markers and sensitive files are found with their lines`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        val token = "ghp_" + body(36)
        stage(sandbox, repo, "src/Main.kt", "fun main() {}\nval key = \"$token\"\n")
        stage(sandbox, repo, "notes.md", "a\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> topic\n")
        stage(sandbox, repo, ".env", "A=1\n")
        stage(sandbox, repo, "clean.txt", "nothing to see\n")
        val report = Rig(sandbox).scan(repo)
        assertEquals(
            setOf(
                "SECRET:src/Main.kt:2",
                "CONFLICT_MARKER:notes.md:2",
                "CONFLICT_MARKER:notes.md:6",
                "SENSITIVE_FILE:.env:-",
            ),
            report.findings.map { it.key() }.toSet(),
        )
        assertFalse(report.clean)
        assertEquals(4, report.blocking.size)
        assertFalse(report.findings.toString().contains(token))
        assertEquals("ghp_****…${token.takeLast(4)}", report.findings.first { it.rule == GuardRule.SECRET }.excerpt)
    }

    @Test
    fun `only what is staged is scanned`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "staged.txt", "fine\n")
        write(repo, "loose.txt", "key = ghp_" + body(36) + "\n")
        write(repo, ".env", "A=1\n")
        val report = Rig(sandbox).scan(repo)
        assertEquals(emptyList(), report.findings)
        assertTrue(report.clean)
    }

    @Test
    fun `a rule that is off is not run`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "k.txt", "key = ghp_" + body(36) + "\n")
        stage(sandbox, repo, ".env", "A=1\n")
        val rig = Rig(sandbox)
        val noSecrets = rig.scan(repo, options = GuardOptions(rules = GuardRule.entries.toSet() - GuardRule.SECRET))
        assertEquals(listOf(GuardRule.SENSITIVE_FILE), noSecrets.findings.map { it.rule })
        assertEquals(emptyList(), rig.scan(repo, options = GuardOptions(rules = emptySet())).findings)
    }

    @Test
    fun `a binary file gets only the name and size checks`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        val bytes = "ghp_${body(36)}".toByteArray() + byteArrayOf(0, 1, 2, 0)
        Files.write(repo.resolve("server.pem"), bytes)
        Files.write(repo.resolve("blob.bin"), bytes)
        sandbox.git(repo, "add", "server.pem", "blob.bin")
        val report = Rig(sandbox).scan(repo)
        assertEquals(listOf("SENSITIVE_FILE:server.pem:-"), report.findings.map { it.key() })
    }

    @Test
    fun `large files warn over the first limit and block over the second`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        Files.write(repo.resolve("medium.dat"), ByteArray(3000) { (it % 251).toByte() })
        Files.write(repo.resolve("huge.dat"), ByteArray(9000) { (it % 251).toByte() })
        Files.write(repo.resolve("small.dat"), ByteArray(10) { 1 })
        sandbox.git(repo, "add", "medium.dat", "huge.dat", "small.dat")
        val options = GuardOptions(rules = setOf(GuardRule.LARGE_FILE), warnBytes = 2000, blockBytes = 8000)
        val report = Rig(sandbox).scan(repo, options = options)
        assertEquals(
            listOf("huge.dat" to GuardSeverity.BLOCK, "medium.dat" to GuardSeverity.WARN),
            report.findings.sortedBy { it.path }.map { it.path to it.severity },
        )
        assertTrue(report.findings.first { it.path == "medium.dat" }.excerpt.endsWith("MB"))
    }

    @Test
    fun `names that differ only in case collide, against the tree and among the new files`(sandbox: GitSandbox) =
        runBlocking {
            val repo = sandbox.repo { commit("one") { file("docs/Readme.md", "1\n") } }
            val blob = sandbox.git(repo, "rev-parse", "HEAD:docs/Readme.md").trim()
            // A case-sensitive file system can hold both names, and the index can on any: add them there directly.
            for (path in listOf("docs/README.md", "other/A.txt", "other/a.txt")) {
                sandbox.git(repo, "update-index", "--add", "--cacheinfo", "100644,$blob,$path")
            }
            val report = Rig(sandbox).scan(repo)
            val collisions = report.findings.filter { it.rule == GuardRule.CASE_COLLISION }
            assertEquals(setOf("docs/README.md", "other/A.txt", "other/a.txt"), collisions.map { it.path }.toSet())
            assertTrue(collisions.all { it.severity == GuardSeverity.WARN && it.line == null })
            assertTrue(report.clean)
        }

    @Test
    fun `renaming a file only by case is not a collision`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("Readme.md", "hello\n") } }
        sandbox.git(repo, "mv", "Readme.md", "tmp.md")
        sandbox.git(repo, "mv", "tmp.md", "README.md")
        assertEquals(emptyList(), Rig(sandbox).scan(repo).findings)
    }

    @Test
    fun `fingerprints are the same when the secret moves to another line`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        val line = "key = \"ghp_${body(36)}\""
        val rig = Rig(sandbox)
        stage(sandbox, repo, "k.txt", "$line\n")
        val first = rig.scan(repo).findings.single()
        stage(sandbox, repo, "k.txt", "one\ntwo\nthree\n$line\n")
        val second = rig.scan(repo).findings.single()
        assertEquals(1, first.line)
        assertEquals(4, second.line)
        assertEquals(first.fingerprint, second.fingerprint)
    }

    // --- sources -------------------------------------------------------------------------------------------------

    @Test
    fun `a selection is scanned as it would be committed, leaving the index alone`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", (1..30).joinToString("\n") + "\n") } }
        write(repo, "a.txt", (1..30).joinToString("\n") { if (it == 3) "key = ghp_${body(36)}" else "$it" } + "\n")
        write(repo, "b.txt", "key = ghp_${body(36)}\n")
        val rig = Rig(sandbox)
        val before = sandbox.git(repo, "status", "--porcelain")
        val onlyA = ChangeSelection(listOf(FileSelection("a.txt", Scope.WholeFile)))
        val report = rig.scan(repo, GuardTarget.Selection(onlyA))
        assertEquals(listOf("SECRET:a.txt:3"), report.findings.map { it.key() })
        assertEquals(before, sandbox.git(repo, "status", "--porcelain"))
        val none = rig.scan(repo, GuardTarget.Selection(ChangeSelection(emptyList())))
        assertTrue(none.findings.isEmpty())
    }

    @Test
    fun `a range is one combined diff of every commit in it`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("base") { file("a.txt", "1\n") } }
        val base = sandbox.git(repo, "rev-parse", "HEAD").trim()
        val token = "ghp_" + body(36)
        stage(sandbox, repo, "k.txt", "key = $token\n")
        sandbox.git(repo, "commit", "-m", "adds a key")
        // A later commit removes it again: the combined diff has no trace, as a push of both would show nothing.
        sandbox.git(repo, "rm", "-q", "k.txt")
        sandbox.git(repo, "commit", "-m", "removes it")
        stage(sandbox, repo, "m.txt", "<<<<<<< HEAD\n")
        sandbox.git(repo, "commit", "-m", "marker")
        stage(sandbox, repo, ".env", "A=1\n")
        sandbox.git(repo, "commit", "-m", "env")
        val report = Rig(sandbox).scan(repo, GuardTarget.Range(base, "HEAD"))
        assertEquals(
            setOf("CONFLICT_MARKER:m.txt:1", "SENSITIVE_FILE:.env:-"),
            report.findings.map { it.key() }.toSet(),
        )
        assertEquals(emptyList(), Rig(sandbox).scan(repo, GuardTarget.Range("HEAD", "HEAD")).findings)
    }

    @Test
    fun `a revision that looks like an option is refused`() {
        val threw = runCatching { GuardTarget.Range("--output=x", "HEAD") }.isFailure
        assertTrue(threw)
    }

    @Test
    fun `scanning outside a repository fails instead of comparing files`(sandbox: GitSandbox) = runBlocking {
        val result = Rig(sandbox).guard.scan(sandbox.dir("not-a-repo"), GuardTarget.Staged)
        assertIs<Result.Failure>(result)
    }

    // --- allow-lists -------------------------------------------------------------------------------------------

    @Test
    fun `allow always, local, hides the finding next time and lives in the git dir`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "k.txt", "key = ghp_${body(36)}\n")
        val rig = Rig(sandbox)
        val finding = rig.scan(repo).findings.single()
        rig.guard.allowAlways(repo, finding).getOrThrow()
        val after = rig.scan(repo)
        assertEquals(emptyList(), after.findings)
        assertEquals(listOf(finding.fingerprint), after.allowed.map { it.fingerprint })
        assertTrue(after.clean)
        val file = repo.resolve(".git/graft/guard-allow.json")
        val text = Files.readString(file)
        assertTrue(text.contains("\"version\": 1") && text.contains(finding.fingerprint))
        assertFalse(text.contains(finding.excerpt))
        // Twice is once.
        rig.guard.allowAlways(repo, finding).getOrThrow()
        assertEquals(1, Regex(finding.fingerprint).findAll(Files.readString(file)).count())
    }

    @Test
    fun `allow always, shared, goes to guard toml and patterns allow whole folders`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "k.txt", "key = ghp_${body(36)}\n")
        stage(sandbox, repo, "fixtures/fake/key.txt", "key = ghp_${body(36)}\n")
        stage(sandbox, repo, "fixtures/fake/.env", "A=1\n")
        val rig = Rig(sandbox)
        assertEquals(3, rig.scan(repo).findings.size)
        rig.guard.allowPaths(repo, listOf("fixtures/**")).getOrThrow()
        val open = rig.scan(repo)
        assertEquals(listOf("SECRET:k.txt:1"), open.findings.map { it.key() })
        assertEquals(2, open.allowed.size)
        rig.guard.allowAlways(repo, open.findings.single(), shared = true).getOrThrow()
        assertEquals(emptyList(), rig.scan(repo).findings)
        val toml = Files.readString(repo.resolve(".graft/guard.toml"))
        assertTrue(toml.startsWith("version = 1"))
        assertTrue(toml.contains("fixtures/**") && toml.contains(open.findings.single().fingerprint))
        val allow = rig.guard.allowances(repo).getOrThrow()
        assertEquals(Allowances(setOf(open.findings.single().fingerprint), listOf("fixtures/**")), allow)
    }

    @Test
    fun `allow once is recorded in the op log and allows nothing`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "k.txt", "key = ghp_${body(36)}\n")
        val rig = Rig(sandbox)
        val finding = rig.scan(repo).findings.single()
        rig.guard.allowOnce(repo, finding).getOrThrow()
        val entry = rig.log.entries(repo, 10).getOrThrow().first()
        assertEquals(listOf("graft", "guard", "allow-once", "SECRET", finding.fingerprint), entry.argv)
        assertFalse(entry.toString().contains(finding.excerpt))
        // It is not remembered: the finding is back on the next scan.
        assertEquals(1, rig.scan(repo).findings.size)
    }

    @Test
    fun `an allow-list from a newer Graft or a broken one is left alone`(sandbox: GitSandbox) = runBlocking {
        val repo = sandbox.repo { commit("one") { file("a.txt", "1\n") } }
        stage(sandbox, repo, "k.txt", "key = ghp_${body(36)}\n")
        val rig = Rig(sandbox)
        val finding = rig.scan(repo).findings.single()
        val local = repo.resolve(".git/graft/guard-allow.json")
        Files.createDirectories(local.parent)
        Files.writeString(local, """{"version":2,"allow":[{"fingerprint":"abc"}],"future":1}""")
        val newer = rig.guard.allowAlways(repo, finding)
        assertEquals(GuardProblem.NEWER_FORMAT, ((newer as Result.Failure).error as GitGuardRefusedException).problem)
        assertEquals("""{"version":2,"allow":[{"fingerprint":"abc"}],"future":1}""", Files.readString(local))
        Files.delete(local)
        write(repo, ".graft/guard.toml", "this is not toml {")
        val broken = rig.guard.allowAlways(repo, finding, shared = true)
        val unreadable = (broken as Result.Failure).error as GitGuardRefusedException
        assertEquals(GuardProblem.UNREADABLE_FILE, unreadable.problem)
        assertEquals("this is not toml {", Files.readString(repo.resolve(".graft/guard.toml")))
    }

    // --- size ----------------------------------------------------------------------------------------------------

    @Test
    fun `a ten thousand line diff is scanned as a stream`() {
        val token = "ghp_" + body(36)
        val patch =
            buildString {
                append("diff --git a/big.txt b/big.txt\n--- a/big.txt\n+++ b/big.txt\n@@ -0,0 +1,10000 @@\n")
                for (i in 1..10_000) append(if (i % 2500 == 0) "+key = $token\n" else "+line number $i of the file\n")
            }
        val start = System.nanoTime()
        val findings = GuardDiffScanner.scan(patch.byteInputStream(), secrets = true, markers = true).toList()
        val millis = (System.nanoTime() - start) / 1_000_000
        println("guard scan of 10,000 lines: $millis ms")
        assertEquals(listOf(2500, 5000, 7500, 10000), findings.map { it.line })
        // Generous: the point is that it is nowhere near a second on any runner.
        assertTrue(millis < 5_000, "took $millis ms")
    }
}

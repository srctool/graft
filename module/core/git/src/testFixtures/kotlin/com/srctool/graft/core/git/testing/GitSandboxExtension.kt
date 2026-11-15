// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.core.git.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.nio.file.Files

/**
 * Gives a test method a fresh [GitSandbox] parameter and deletes it afterwards:
 * ```
 * @ExtendWith(GitSandboxExtension::class)
 * class MyTest {
 *     @Test fun works(sandbox: GitSandbox) { … }
 * }
 * ```
 */
public class GitSandboxExtension :
    ParameterResolver,
    AfterEachCallback {
    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == GitSandbox::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): GitSandbox {
        val sandbox = GitSandbox(Files.createTempDirectory("graft-sandbox-"))
        extensionContext.getStore(NAMESPACE).put(KEY, sandbox)
        return sandbox
    }

    override fun afterEach(context: ExtensionContext) {
        val sandbox = context.getStore(NAMESPACE).remove(KEY, GitSandbox::class.java) ?: return
        sandbox.root.toFile().deleteRecursively()
        if (sandbox.violations.isNotEmpty()) throw AssertionError(sandbox.violations.joinToString("\n"))
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(GitSandboxExtension::class.java)
        const val KEY = "sandbox"
    }
}

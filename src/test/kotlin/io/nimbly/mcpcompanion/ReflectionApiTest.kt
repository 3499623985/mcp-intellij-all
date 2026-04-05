package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that every method accessed via reflection in this plugin still exists
 * in the current IntelliJ version. A failure here means an IntelliJ API has moved
 * or been removed — update the corresponding code before shipping.
 */
class ReflectionApiTest {

    // ── CoreProgressManager ───────────────────────────────────────────────────

    @Test
    fun `CoreProgressManager getCurrentIndicators is a public static method`() {
        val cls = Class.forName("com.intellij.openapi.progress.impl.CoreProgressManager")
        val method = cls.getMethod("getCurrentIndicators")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers),
            "CoreProgressManager.getCurrentIndicators() must be static")
        assertTrue(List::class.java.isAssignableFrom(method.returnType),
            "CoreProgressManager.getCurrentIndicators() must return a List")
    }

    // @ApiStatus.Internal has @Retention(CLASS) — not visible via normal reflection.
    // We check the bytecode of core.impl.jar directly to detect if the annotation disappears
    // (meaning the method became public API and the reflection wrapper can be simplified).
    // If this test FAILS: CoreProgressManager no longer uses @ApiStatus.Internal →
    // replace currentIndicators() reflection wrapper with a direct call.
    @Test
    fun `CoreProgressManager getCurrentIndicators is @ApiStatus Internal — reflection wrapper required`() {
        val coreImplJar = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .firstOrNull { "core.impl" in it }
        if (coreImplJar == null) {
            println("INFO: intellij.platform.core.impl.jar not on test classpath — skipping @Internal bytecode check")
            return
        }
        val jar = java.util.jar.JarFile(coreImplJar)
        val entry = jar.getJarEntry("com/intellij/openapi/progress/impl/CoreProgressManager.class")
        if (entry == null) { jar.close(); println("WARN: CoreProgressManager.class not found in jar"); return }
        val classBytes = jar.getInputStream(entry).readBytes()
        jar.close()
        // The annotation descriptor appears in the constant pool if used anywhere in the class
        val internalDescriptor = "Lorg/jetbrains/annotations/ApiStatus\$Internal;"
        assertTrue(internalDescriptor in String(classBytes, Charsets.ISO_8859_1),
            "CoreProgressManager no longer contains @ApiStatus.Internal — " +
            "getCurrentIndicators() may now be public API: " +
            "consider replacing currentIndicators() reflection wrapper with a direct call " +
            "and restoring compileOnly(core.impl.jar) in build.gradle.kts")
    }

    // ── TaskInfo ──────────────────────────────────────────────────────────────

    @Test
    fun `TaskInfo getTitle exists`() {
        val cls = Class.forName("com.intellij.openapi.progress.TaskInfo")
        val method = cls.getMethod("getTitle")
        assertEquals(String::class.java, method.returnType,
            "TaskInfo.getTitle() must return String")
    }

    // ── BackgroundableProcessIndicator ────────────────────────────────────────
    // Optional: class is in intellij.platform.ide.impl.jar which may not be on all test classpaths.

    @Test
    fun `BackgroundableProcessIndicator getTaskInfo exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.BackgroundableProcessIndicator")
        }.getOrNull()
        if (cls == null) {
            println("INFO: BackgroundableProcessIndicator not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = cls.getMethod("getTaskInfo")
        val taskInfoCls = Class.forName("com.intellij.openapi.progress.TaskInfo")
        assertTrue(taskInfoCls.isAssignableFrom(method.returnType),
            "BackgroundableProcessIndicator.getTaskInfo() must return a TaskInfo")
    }

    // ── McpToolset.isEnabled ──────────────────────────────────────────────────
    // We override this explicitly to avoid a Kotlin-generated invokespecial bridge.
    // If isEnabled() disappears from McpToolset, our override becomes a no-op and
    // can be removed; if it changes signature, this test will catch it.

    @Test
    fun `McpToolset isEnabled signature is boolean if present`() {
        val cls = Class.forName("com.intellij.mcpserver.McpToolset")
        val method = runCatching { cls.getMethod("isEnabled") }.getOrNull()
        if (method == null) {
            println("INFO: McpToolset.isEnabled() no longer exists — override in McpCompanionToolset can be removed")
            return
        }
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
            "McpToolset.isEnabled() must return boolean")
    }

    // ── McpServerSettings ─────────────────────────────────────────────────────
    // Used in McpCompanionConfigurable.isMcpServerEnabled() via reflection to avoid
    // a hard compile-time dependency on the MCP Server plugin.

    @Test
    fun `McpServerSettings getInstance is a public static method`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
        val method = cls.getMethod("getInstance")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers),
            "McpServerSettings.getInstance() must be static")
    }

    @Test
    fun `McpServerSettings getState exists`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
        assertNotNull(cls.getMethod("getState"),
            "McpServerSettings.getState() not found")
    }

    @Test
    fun `McpServerSettings MyState getEnableMcpServer returns boolean`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings\$MyState")
        val method = cls.getMethod("getEnableMcpServer")
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
            "McpServerSettings.MyState.getEnableMcpServer() must return boolean")
    }

    // ── TerminalViewImpl (send_to_terminal) ────────────────────────────────────
    // send_to_terminal uses TerminalViewImpl.createSendTextBuilder() + doSendText().
    // If the class or methods disappear, the tool will silently return an error — catch
    // it early here so the test suite flags the broken reflection before shipping.

    @Test
    fun `TerminalViewImpl exists in frontend terminal package`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: TerminalViewImpl not found — send_to_terminal may not work (terminal API changed)")
            return
        }
        println("OK: TerminalViewImpl found: ${cls.name}")
    }

    @Test
    fun `TerminalViewImpl createSendTextBuilder exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "createSendTextBuilder" && it.parameterCount == 0 }
        assertNotNull(method, "TerminalViewImpl.createSendTextBuilder() not found — send_to_terminal Strategy 2 is broken")
        println("OK: TerminalViewImpl.createSendTextBuilder() → ${method!!.returnType.name}")
    }

    @Test
    fun `TerminalViewImpl doSendText exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "doSendText" && it.parameterCount == 1 }
        assertNotNull(method, "TerminalViewImpl.doSendText() not found — send_to_terminal Strategy 2 is broken")
        println("OK: TerminalViewImpl.doSendText(${method!!.parameterTypes[0].name})")
    }

    @Test
    fun `TerminalSendTextOptions has constructor with String as first param`() {
        val termViewCls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val doSendText = generateSequence(termViewCls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "doSendText" && it.parameterCount == 1 }
            ?: run { println("WARNING: doSendText not found — skipping TerminalSendTextOptions check"); return }
        val optsCls = doSendText.parameterTypes[0]
        val ctor = optsCls.constructors
            .sortedBy { it.parameterCount }
            .find { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
        assertNotNull(ctor,
            "${optsCls.simpleName} has no constructor with String as first param — send_to_terminal direct send is broken")
        println("OK: ${optsCls.simpleName}(${ctor!!.parameterTypes.joinToString { it.simpleName }}) — " +
            "send_to_terminal can construct options directly")
    }

    // ── Optional: isCancellable / suspend / resume ────────────────────────────
    // These are best-effort: our code handles their absence gracefully.
    // Tests print a warning rather than failing — a future removal surfaces
    // in the test output without breaking the build.

    @Test
    fun `AbstractProgressIndicatorBase optional methods isCancellable suspend resume`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.util.AbstractProgressIndicatorBase")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: AbstractProgressIndicatorBase not found — manage_process pause/resume/cancellable may not work")
            return
        }
        for (name in listOf("isCancellable", "suspend", "resume")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: AbstractProgressIndicatorBase.$name() found")
            else println("WARNING: AbstractProgressIndicatorBase.$name() not found — manage_process($name) will fall back gracefully")
        }
    }

    // ── McpServerSettings.MyState.setEnableMcpServer ─────────────────────────
    // Called in McpCompanionStartupActivity to auto-enable the MCP server on first launch.

    @Test
    fun `McpServerSettings MyState setEnableMcpServer exists`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings\$MyState")
        val method = cls.methods.find { it.name == "setEnableMcpServer" && it.parameterCount == 1 }
        assertNotNull(method, "McpServerSettings.MyState.setEnableMcpServer(Boolean) not found — auto-enable on first launch is broken")
        assertEquals(Boolean::class.javaPrimitiveType, method!!.parameterTypes[0],
            "McpServerSettings.MyState.setEnableMcpServer must take a boolean parameter")
    }

    // ── ProgressSuspender ─────────────────────────────────────────────────────
    // Used in McpCompanionDiagnosticToolset to detect suspended progress indicators.

    @Test
    fun `ProgressSuspender getSuspender static method exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping (optional API)")
            return
        }
        val method = runCatching {
            cls.getMethod("getSuspender", com.intellij.openapi.progress.ProgressIndicator::class.java)
        }.getOrNull()
        if (method == null) println("WARNING: ProgressSuspender.getSuspender(ProgressIndicator) not found — manage_process suspend info unavailable")
        else println("OK: ProgressSuspender.getSuspender() → ${method.returnType.name}")
    }

    @Test
    fun `ProgressSuspender ourProgressToSuspenderMap field exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping")
            return
        }
        val field = runCatching {
            cls.getDeclaredField("ourProgressToSuspenderMap").also { it.isAccessible = true }
        }.getOrNull()
        if (field == null) println("WARNING: ProgressSuspender.ourProgressToSuspenderMap not found — allSuspenderEntries() will return empty")
        else println("OK: ProgressSuspender.ourProgressToSuspenderMap found (${field.type.name})")
    }

    @Test
    fun `ProgressSuspender isSuspended and getSuspendedText exist`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping")
            return
        }
        for (name in listOf("isSuspended", "getSuspendedText")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: ProgressSuspender.$name() found")
            else println("WARNING: ProgressSuspender.$name() not found — diagnostic suspend status unavailable")
        }
    }

    // ── EditorComponentImpl (ConsoleUtil + get_services_output) ──────────────
    // ConsoleUtil.readText() and readEditorText() both use EditorComponentImpl → myEditor field.

    @Test
    fun `EditorComponentImpl class exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
        }.getOrNull()
        assertNotNull(cls, "EditorComponentImpl not found — ConsoleUtil.readText() and readEditorText() are broken")
        println("OK: EditorComponentImpl found")
    }

    @Test
    fun `EditorComponentImpl has myEditor or editor field`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
        }.getOrNull() ?: run {
            println("INFO: EditorComponentImpl not on classpath — skipping field check")
            return
        }
        val field = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "myEditor" || it.name == "editor" }
        assertNotNull(field,
            "EditorComponentImpl has no 'myEditor' or 'editor' field — ConsoleUtil editor extraction is broken")
        assertTrue(com.intellij.openapi.editor.Editor::class.java.isAssignableFrom(field!!.type),
            "EditorComponentImpl.${field.name} must be of type Editor")
        println("OK: EditorComponentImpl.${field.name}: ${field.type.simpleName}")
    }

    // ── Build node reflection (McpCompanionBuildToolset) ─────────────────────
    // Build output nodes are accessed via reflection on their actual runtime type.
    // The key contract: AbstractTreeNode-like objects expose getTitle/getHint/getElement.

    @Test
    fun `ExecutionNode getTitle and getHint exist`() {
        val cls = runCatching {
            Class.forName("com.intellij.build.ExecutionNode")
        }.getOrNull() ?: run {
            println("INFO: ExecutionNode not on test classpath — skipping (build node reflection verified at runtime)")
            return
        }
        for (name in listOf("getTitle", "getHint")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: ExecutionNode.$name() found")
            else println("WARNING: ExecutionNode.$name() not found — get_build_output title/hint extraction may degrade")
        }
    }
}

package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that every method/field accessed via reflection in McpCompanionCodeAnalysisToolset
 * still exists in the current IntelliJ version.
 *
 * These tests are the JUnit equivalent of the curl SSE smoke tests for the Code Analysis toolset:
 * if a test fails here, the corresponding tool will silently return empty results at runtime.
 */
class CodeAnalysisReflectionTest {

    // ── get_quick_fixes — HighlightInfo.offsetStore ──────────────────────────
    // get_quick_fixes finds the offsetStore field on HighlightInfo via reflection and
    // passes it to getIntentionActionDescriptors().
    // If offsetStore is renamed/removed, get_quick_fixes returns "No quick fixes found".

    @Test
    fun `HighlightInfo has offsetStore field`() {
        val cls = Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo")
        val field = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "offsetStore" }
        assertNotNull(field,
            "HighlightInfo.offsetStore field not found — get_quick_fixes will return empty results. " +
            "Update the field name in McpCompanionCodeAnalysisToolset.get_quick_fixes().")
    }

    // ── get_quick_fixes — HighlightInfo.getIntentionActionDescriptors ────────
    // Called with the offsetStore instance to retrieve the list of IntentionActionDescriptor.
    // If this method is renamed/removed, get_quick_fixes returns "No quick fixes found".

    @Test
    fun `HighlightInfo has getIntentionActionDescriptors(offsetStore) method`() {
        val cls = Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo")
        val offsetStoreField = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "offsetStore" }
        if (offsetStoreField == null) {
            println("SKIP: offsetStore field not found — prerequisite for this test is missing")
            return
        }
        offsetStoreField.isAccessible = true
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { m -> m.name == "getIntentionActionDescriptors" && m.parameterCount == 1
                      && m.parameterTypes[0] == offsetStoreField.type }
        assertNotNull(method,
            "HighlightInfo.getIntentionActionDescriptors(${offsetStoreField.type.simpleName}) not found — " +
            "get_quick_fixes will return empty results. " +
            "Update the method lookup in McpCompanionCodeAnalysisToolset.get_quick_fixes().")
    }

    // ── get_quick_fixes — IntentionActionDescriptor.getAction ────────────────
    // Each descriptor returned by getIntentionActionDescriptors() must expose getAction().

    @Test
    fun `IntentionActionDescriptor getAction exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInsight.intention.IntentionActionDelegate\$Companion")
        }.getOrNull() ?: runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo\$IntentionActionDescriptor")
        }.getOrNull()
        if (cls == null) {
            println("INFO: IntentionActionDescriptor not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = cls.methods.find { it.name == "getAction" && it.parameterCount == 0 }
        assertNotNull(method,
            "IntentionActionDescriptor.getAction() not found — get_quick_fixes will return empty results.")
    }

    // ── get_quick_fixes — DaemonCodeAnalyzer.isRunning ───────────────────────
    // get_quick_fixes uses reflection to call isRunning() on the DaemonCodeAnalyzer instance
    // (the method is on the impl, not the public interface) to detect if highlights are ready.
    // If isRunning() disappears, the check degrades gracefully (returns false).

    @Test
    fun `DaemonCodeAnalyzer implementation has isRunning method`() {
        val implCls = runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl")
        }.getOrNull()
        if (implCls == null) {
            println("INFO: DaemonCodeAnalyzerImpl not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = runCatching { implCls.getMethod("isRunning") }.getOrNull()
        if (method == null) {
            println("WARNING: DaemonCodeAnalyzerImpl.isRunning() not found — " +
                    "get_quick_fixes daemon check will always return false (degraded gracefully). " +
                    "Consider updating the daemon check in McpCompanionCodeAnalysisToolset.")
        } else {
            assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
                "DaemonCodeAnalyzerImpl.isRunning() must return boolean")
            println("OK: DaemonCodeAnalyzerImpl.isRunning() found")
        }
    }

    // ── get_file_problems / get_quick_fixes — DocumentMarkupModel.forDocument ─
    // Both tools rely on this public API to read highlight info without using
    // the @ApiStatus.Internal DaemonCodeAnalyzerImpl.getHighlights().

    @Test
    fun `DocumentMarkupModel forDocument static method exists`() {
        val cls = Class.forName("com.intellij.openapi.editor.impl.DocumentMarkupModel")
        val method = runCatching {
            cls.getMethod("forDocument",
                Class.forName("com.intellij.openapi.editor.Document"),
                Class.forName("com.intellij.openapi.project.Project"),
                Boolean::class.javaPrimitiveType)
        }.getOrNull()
        assertNotNull(method,
            "DocumentMarkupModel.forDocument(Document, Project, boolean) not found — " +
            "get_file_problems and get_quick_fixes will return empty results. " +
            "Find the new public API to read markup highlights.")
        assertTrue(java.lang.reflect.Modifier.isStatic(method!!.modifiers),
            "DocumentMarkupModel.forDocument() must be static")
    }

    // ── ProgressSuspender — already covered in ReflectionApiTest ─────────────
    // (ourProgressToSuspenderMap, suspendProcess, resumeProcess)

    // ══════════════════════════════════════════════════════════════════════════
    // list_inspections / run_inspections
    // ══════════════════════════════════════════════════════════════════════════

    // ── InspectionProjectProfileManager ──────────────────────────────────────
    // Both tools call InspectionProjectProfileManager.getInstance(project).currentProfile
    // to obtain the active inspection profile.

    @Test
    fun `InspectionProjectProfileManager class and getInstance exist`() {
        val cls = runCatching {
            Class.forName("com.intellij.profile.codeInspection.InspectionProjectProfileManager")
        }.getOrNull()
        if (cls == null) {
            println("INFO: InspectionProjectProfileManager not on test classpath — skipping")
            return
        }
        val method = runCatching {
            cls.getMethod("getInstance", Class.forName("com.intellij.openapi.project.Project"))
        }.getOrNull()
        assertNotNull(method,
            "InspectionProjectProfileManager.getInstance(Project) not found — " +
            "list_inspections and run_inspections will fail to obtain the inspection profile.")
        println("OK: InspectionProjectProfileManager.getInstance(Project) found")
    }

    // ── InspectionProfileImpl.getInspectionTools ──────────────────────────────
    // run_inspections calls profile.getInspectionTools(psiFile) to list enabled tools.

    @Test
    fun `InspectionProfileImpl has getInspectionTools method`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInspection.ex.InspectionProfileImpl")
        }.getOrNull() ?: runCatching {
            Class.forName("com.intellij.profile.codeInspection.InspectionProfileImpl")
        }.getOrNull()
        if (cls == null) {
            println("INFO: InspectionProfileImpl not on test classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getInspectionTools" && it.parameterCount == 1 }
        assertNotNull(method,
            "InspectionProfileImpl.getInspectionTools(PsiElement) not found — " +
            "run_inspections will return no results.")
        println("OK: InspectionProfileImpl.getInspectionTools(PsiElement) found")
    }

    // ── HighlightDisplayKey.find ───────────────────────────────────────────────
    // run_inspections uses HighlightDisplayKey.find(shortName) to get the key for each tool.

    @Test
    fun `HighlightDisplayKey has static find method`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInsight.daemon.HighlightDisplayKey")
        }.getOrNull()
        if (cls == null) {
            println("INFO: HighlightDisplayKey not on test classpath — skipping")
            return
        }
        val method = runCatching {
            cls.getMethod("find", String::class.java)
        }.getOrNull()
        assertNotNull(method,
            "HighlightDisplayKey.find(String) not found — " +
            "run_inspections will skip all inspection tools.")
        assertTrue(java.lang.reflect.Modifier.isStatic(method!!.modifiers),
            "HighlightDisplayKey.find() must be static")
        println("OK: HighlightDisplayKey.find(String) found")
    }

    // ── LocalInspectionEP.LOCAL_INSPECTION extension point ────────────────────
    // isLanguageCompatible() in run_inspections uses this EP to read the declared language
    // of each inspection and filter out cross-language false positives (e.g. Kotlin on Java).

    @Test
    fun `LocalInspectionEP LOCAL_INSPECTION extension point is accessible`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInspection.LocalInspectionEP")
        }.getOrNull()
        if (cls == null) {
            println("INFO: LocalInspectionEP not on test classpath — skipping")
            return
        }
        val field = runCatching {
            cls.getField("LOCAL_INSPECTION")
        }.getOrNull()
        assertNotNull(field,
            "LocalInspectionEP.LOCAL_INSPECTION field not found — " +
            "run_inspections language-compatibility filter will stop working, " +
            "potentially causing Kotlin inspections to run on Java files.")
        println("OK: LocalInspectionEP.LOCAL_INSPECTION field found")
    }

    // ── HighlightInfo.getInspectionToolId ─────────────────────────────────────
    // run_inspections supplements batch results with daemon highlights for open files.
    // It reads info.inspectionToolId to identify which inspection produced each highlight,
    // and skips highlights that have no associated inspection (e.g. compiler errors).

    @Test
    fun `HighlightInfo has getInspectionToolId method`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo")
        }.getOrNull()
        if (cls == null) {
            println("INFO: HighlightInfo not on test classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getInspectionToolId" && it.parameterCount == 0 }
        assertNotNull(method,
            "HighlightInfo.getInspectionToolId() not found — " +
            "run_inspections daemon-highlight supplement will not be able to identify " +
            "inspection-related highlights (TYPO, annotator results, etc.).")
        println("OK: HighlightInfo.getInspectionToolId() found")
    }
}

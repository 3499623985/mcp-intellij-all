package io.nimbly.mcpcompanion.util

import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlin.coroutines.coroutineContext

/**
 * Resolves the target project for a tool call.
 *
 * When [projectPath] is provided, searches [ProjectManager.getOpenProjects] for an open project
 * whose `basePath` matches [projectPath] (exact match, or prefix of [projectPath] followed by "/").
 * If no open project matches — or [projectPath] is null/blank — falls back to the MCP context
 * project (`coroutineContext.project`), the "active" project in the IDE.
 *
 * This lets each tool deterministically target a specific project when a single IntelliJ JVM
 * hosts several open projects, while staying fully backwards compatible with callers that don't
 * pass a path.
 */
suspend fun resolveProject(projectPath: String? = null): Project {
    val fallback = coroutineContext.project
    if (projectPath.isNullOrBlank()) return fallback
    val normalized = projectPath.trim().removeSuffix("/")
    val projects = ProjectManager.getInstance().openProjects
    projects.firstOrNull { it.basePath?.removeSuffix("/") == normalized }?.let { return it }
    projects.firstOrNull { base ->
        val bp = base.basePath?.removeSuffix("/") ?: return@firstOrNull false
        normalized.startsWith("$bp/")
    }?.let { return it }
    return fallback
}

package io.nimbly.mcpcompanion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.TaskInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tracks background processes shown in IntelliJ's "Processes" popup (status bar).
 *
 * IntelliJ's `CoreProgressManager.getCurrentIndicators()` misses external-system tasks
 * (Gradle sync, Maven import, "Indexing files" on sync). The status bar however uses
 * [com.intellij.openapi.wm.ex.StatusBarEx.getBackgroundProcesses] which aggregates everything.
 *
 * We poll it every [POLL_INTERVAL_MS] ms to:
 *  - record tasks as soon as they appear → exposes them with `startedAgoMs` + `progress`
 *  - detect disappearance → move to `finished`, keep for [FINISHED_TTL_MS] ms, cap at [FINISHED_MAX]
 */
@Service(Service.Level.PROJECT)
class ProcessTracker(private val project: Project) : Disposable {

    data class ActiveTask(
        val title: String,
        val startedAt: Long,
        val indicator: ProgressIndicator,
    )

    data class FinishedTask(
        val title: String,
        val startedAt: Long,
        val endedAt: Long,
    ) {
        val ranMs: Long get() = endedAt - startedAt
    }

    private val active = ConcurrentHashMap<ProgressIndicator, ActiveTask>()
    private val finished = ConcurrentLinkedDeque<FinishedTask>() // newest first

    private val future: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay({ poll() }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)

    /** Current in-progress tasks (safe snapshot). */
    fun active(): List<ActiveTask> = active.values.toList()

    /** Recently finished tasks, newest first, capped at [limit]. */
    fun recentlyFinished(limit: Int = FINISHED_MAX): List<FinishedTask> {
        pruneFinished(System.currentTimeMillis())
        return finished.take(limit)
    }

    /** Lookup active indicator by title (partial, case-insensitive) — used by manage_process. */
    fun findActiveByTitle(needle: String): ActiveTask? =
        active.values.firstOrNull { it.title.contains(needle, ignoreCase = true) }

    private fun poll() {
        try {
            val sb = WindowManager.getInstance().getStatusBar(project) ?: return
            @Suppress("UNCHECKED_CAST")
            val processes = runCatching {
                // StatusBarEx.getBackgroundProcesses(): List<Pair<TaskInfo, ProgressIndicator>>
                sb.javaClass.getMethod("getBackgroundProcesses").invoke(sb)
                    as? List<com.intellij.openapi.util.Pair<TaskInfo, ProgressIndicator>>
            }.getOrNull() ?: return

            val now = System.currentTimeMillis()
            val seen = HashSet<ProgressIndicator>(processes.size)

            for (pair in processes) {
                val info = pair.first ?: continue
                val ind = pair.second ?: continue
                val title = info.title?.takeIf { it.isNotBlank() } ?: continue
                seen.add(ind)
                active.computeIfAbsent(ind) { ActiveTask(title, now, ind) }
            }

            // Move disappeared tasks to finished.
            val iter = active.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key !in seen) {
                    iter.remove()
                    finished.addFirst(FinishedTask(entry.value.title, entry.value.startedAt, now))
                }
            }

            pruneFinished(now)
        } catch (_: Throwable) {
            // Never let a poll failure break the scheduler.
        }
    }

    private fun pruneFinished(now: Long) {
        val cutoff = now - FINISHED_TTL_MS
        while (true) {
            val oldest = finished.peekLast() ?: break
            if (oldest.endedAt < cutoff) finished.pollLast() else break
        }
        while (finished.size > FINISHED_MAX_RETAINED) finished.pollLast()
    }

    override fun dispose() {
        future.cancel(false)
        active.clear()
        finished.clear()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val FINISHED_TTL_MS = 3 * 60 * 1000L  // 3 minutes
        const val FINISHED_MAX = 10                          // returned by default
        private const val FINISHED_MAX_RETAINED = 50         // hard cap in memory

        fun getInstance(project: Project): ProcessTracker = project.service()
    }
}

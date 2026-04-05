package io.nimbly.mcpcompanion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import java.io.OutputStreamWriter
import java.net.HttpURLConnection

object McpCompanionTelemetry {

    private val LOG = Logger.getInstance(McpCompanionTelemetry::class.java)

    private const val BASE_URL = "https://mcp-intellij-g23v4uokg-maxime-hamm-projects.vercel.app"
    private const val TRACK_URL = "$BASE_URL/api/track"
    const val STATS_URL = "$BASE_URL/api/stats"

    private const val SEND_TIMEOUT_MS = 3_000
    private const val FETCH_TIMEOUT_MS = 8_000

    /** Fire-and-forget: sends a tool_used event in a daemon thread. Silent on failure. */
    fun trackIfEnabled(toolName: String) {
        val settings = McpCompanionSettings.getInstance()
        if (!settings.isTelemetryEnabled()) return

        val clientId = settings.getAnonymousId()
        val version  = pluginVersion()
        val payload  = """{"client_id":"$clientId","tool_name":"$toolName","plugin_version":"$version"}"""

        Thread {
            try {
                val conn = openConnection(TRACK_URL)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = SEND_TIMEOUT_MS
                conn.readTimeout    = SEND_TIMEOUT_MS
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                val code = conn.responseCode
                conn.disconnect()
                LOG.info("Telemetry sent: $toolName → HTTP $code")
            } catch (e: Exception) {
                LOG.warn("Telemetry failed for $toolName: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Fetches aggregated stats from the server.
     * Blocking — always call from a background thread.
     * Returns null on any error.
     */
    fun fetchGlobalStats(): Map<String, Int>? {
        return try {
            val conn = openConnection(STATS_URL)
            conn.requestMethod = "GET"
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout    = FETCH_TIMEOUT_MS
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseJsonIntMap(body)
        } catch (_: Exception) { null }
    }

    /** Opens a connection respecting the proxy configured in IntelliJ settings. */
    private fun openConnection(url: String): HttpURLConnection =
        HttpConfigurable.getInstance().openConnection(url) as HttpURLConnection

    private fun pluginVersion(): String =
        McpCompanionTelemetry::class.java.`package`?.implementationVersion ?: "dev"

    /** Minimal {"key": number} JSON parser — no external dependency needed. */
    private fun parseJsonIntMap(json: String): Map<String, Int> {
        val result  = mutableMapOf<String, Int>()
        val pattern = Regex(""""([^"]+)"\s*:\s*(\d+)""")
        pattern.findAll(json).forEach { m ->
            result[m.groupValues[1]] = m.groupValues[2].toIntOrNull() ?: 0
        }
        return result
    }
}

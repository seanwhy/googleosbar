package com.meridian11.books.bentobar.items

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
data class QuotaBucket(
    @SerialName("used_percent") val usedPercent: Float? = null,
    @SerialName("remaining_percent") val remainingPercent: Float? = null,
    @SerialName("remaining_fraction") val remainingFraction: Float? = null,
    @SerialName("reset_in_sec") val resetInSec: Long? = null,
    @SerialName("reset_time") val resetTime: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val unlimited: Boolean = false,
) {
    val remaining: Float get() = remainingPercent ?: ((1f - (usedPercent ?: 0f) / 100f) * 100f).coerceIn(0f, 100f)
    val fraction: Float get() = (remaining / 100f).coerceIn(0f, 1f)
    fun formattedCountdown(): String = formatCountdown(resetInSec)
}

@Serializable
data class AntigravityQuota(
    val status: String = "ok",
    val model: String = "Gemini 4 Argon",
    @SerialName("claude_5h") val claude5h: QuotaBucket? = null,
    @SerialName("claude_weekly") val claudeWeekly: QuotaBucket? = null,
    @SerialName("gemini_5h") val gemini5h: QuotaBucket? = null,
    @SerialName("gemini_weekly") val geminiWeekly: QuotaBucket? = null,
    val error: String? = null,
    val reason: String? = null,
)

@Serializable
data class CodexWindow(
    @SerialName("used_percent") val usedPercent: Float? = null,
    @SerialName("remaining_percent") val remainingPercent: Float? = null,
    @SerialName("reset_in_sec") val resetInSec: Long? = null,
    @SerialName("reset_at") val resetAt: Long? = null,
) {
    val remaining: Float get() = remainingPercent ?: (100f - (usedPercent ?: 0f)).coerceIn(0f, 100f)
    val fraction: Float get() = (remaining / 100f).coerceIn(0f, 1f)
    fun formattedCountdown(): String = formatCountdown(resetInSec)
}

@Serializable
data class CodexQuota(
    val status: String = "ok",
    @SerialName("plan_type") val planType: String = "plus",
    @SerialName("window_5h") val window5h: CodexWindow? = null,
    @SerialName("window_weekly") val windowWeekly: CodexWindow? = null,
    val error: String? = null,
    val reason: String? = null,
)

@Serializable
data class GrokCycle(
    @SerialName("used_percent") val usedPercent: Float? = null,
    @SerialName("remaining_percent") val remainingPercent: Float? = null,
    @SerialName("reset_in_sec") val resetInSec: Long? = null,
) {
    val remaining: Float get() = remainingPercent ?: (100f - (usedPercent ?: 0f)).coerceIn(0f, 100f)
    val fraction: Float get() = (remaining / 100f).coerceIn(0f, 1f)
    fun formattedCountdown(): String = formatCountdown(resetInSec)
}

@Serializable
data class GrokQuota(
    val status: String = "ok",
    val model: String = "grok-4.7",
    val tier: String = "SuperGrok",
    val cycle: GrokCycle? = null,
    @SerialName("grok_build") val grokBuild: GrokCycle? = null,
    @SerialName("grok_chat") val grokChat: GrokCycle? = null,
    @SerialName("today_tokens") val todayTokens: Long = 0L,
    @SerialName("weekly_tokens") val weeklyTokens: Long = 0L,
    val error: String? = null,
)

@Serializable
data class AiUsageSnapshot(
    @SerialName("updated_at") val updatedAt: Long = 0L,
    val antigravity: AntigravityQuota? = null,
    val codex: CodexQuota? = null,
    val grok: GrokQuota? = null,
) {
    fun isStale(nowSec: Long): Boolean = updatedAt > 0 && (nowSec - updatedAt) > 900L // > 15 minutes
}

internal fun formatCountdown(seconds: Long?): String {
    if (seconds == null) return "–"
    if (seconds <= 0) return "Ready"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}

internal fun formatTokens(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

object AiUsageReader {
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastPollAt = 0L
    @Volatile private var isPolling = false
    private var lastFileMod = 0L
    private var cachedPath: String? = null
    @Volatile private var cached: AiUsageSnapshot? = null

    private val candidatePaths = listOf(
        "/storage/emulated/10/Codes/googleosbar/ai_usage_snapshot.json",
        "/mnt/shared/Codes/googleosbar/ai_usage_snapshot.json",
        "/storage/emulated/0/Android/data/com.meridian11.books.bentobar/files/ai_usage_snapshot.json",
        "/storage/emulated/10/Android/data/com.meridian11.books.bentobar/files/ai_usage_snapshot.json",
        "/mnt/shared/ai_usage_snapshot.json",
        "/sdcard/ai_usage_snapshot.json"
    )

    private fun fetchFromDaemon(): AiUsageSnapshot? {
        val targets = listOf("127.0.0.1", "10.192.190.115", "10.192.190.62", "localhost")
        for (host in targets) {
            val res = runCatching {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, 19876), 400)
                socket.soTimeout = 1500
                val out = socket.getOutputStream()
                out.write("GET / HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                socket.close()
                val jsonStart = raw.indexOf('{')
                val jsonEnd = raw.lastIndexOf('}')
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonStr = raw.substring(jsonStart, jsonEnd + 1)
                    json.decodeFromString<AiUsageSnapshot>(jsonStr)
                } else null
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun readFiles(customPath: String?): AiUsageSnapshot? {
        val filesToTry = mutableListOf<File>()
        if (!customPath.isNullOrBlank()) {
            filesToTry.add(File(customPath))
        } else {
            try {
                Env.app.getExternalFilesDir(null)?.let {
                    filesToTry.add(File(it, "ai_usage_snapshot.json"))
                }
            } catch (_: Throwable) {}
            for (p in candidatePaths) {
                filesToTry.add(File(p))
            }
        }

        for (file in filesToTry) {
            try {
                if (!file.exists() || !file.isFile) continue
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.startsWith("{") && text.endsWith("}")) {
                    return json.decodeFromString<AiUsageSnapshot>(text)
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    fun read(now: Long, customPath: String? = null): AiUsageSnapshot? {
        // Fast path for test custom files
        if (!customPath.isNullOrBlank()) {
            val res = readFiles(customPath)
            if (res != null) {
                cached = res
                return res
            }
            return null
        }

        // Asynchronously poll daemon and fallback to files on background executor
        if (now - lastPollAt > 3000L && !isPolling) {
            isPolling = true
            executor.execute {
                try {
                    val s = fetchFromDaemon() ?: readFiles(customPath)
                    if (s != null) {
                        cached = s
                        lastPollAt = System.currentTimeMillis()
                        Ticker.refresh("ai_antigravity")
                        Ticker.refresh("ai_codex")
                        Ticker.refresh("ai_grok")
                        Ticker.refresh("ai_grok_bot")
                        Ticker.refresh("ai_usage")
                    }
                } catch (_: Throwable) {}
                finally {
                    isPolling = false
                }
            }
        }

        if (cached == null) {
            val fileRes = readFiles(customPath)
            if (fileRes != null) cached = fileRes
        }

        return cached
    }

    /** Clears in-memory cache, primarily for unit testing. */
    fun clearCacheForTesting() {
        lastPollAt = 0L
        lastFileMod = 0L
        cachedPath = null
        cached = null
    }
}

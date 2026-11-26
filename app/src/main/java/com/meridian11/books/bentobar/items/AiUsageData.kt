package com.meridian11.books.bentobar.items

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

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
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("last_ok_at") val lastOkAt: Long? = null,
    @SerialName("last_ok") val lastOk: AntigravityQuota? = null,
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
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("last_ok_at") val lastOkAt: Long? = null,
    @SerialName("last_ok") val lastOk: CodexQuota? = null,
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
    val reason: String? = null,
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("last_ok_at") val lastOkAt: Long? = null,
    @SerialName("last_ok") val lastOk: GrokQuota? = null,
    @SerialName("grok_build_status") val grokBuildStatus: String? = null,
    @SerialName("grok_build_error") val grokBuildError: String? = null,
    @SerialName("grok_build_ok_at") val grokBuildOkAt: Long? = null,
    @SerialName("last_ok_grok_build") val lastOkGrokBuild: GrokCycle? = null,
)

@Serializable
data class AiUsageSnapshot(
    @SerialName("updated_at") val updatedAt: Long = 0L,
    val antigravity: AntigravityQuota? = null,
    val codex: CodexQuota? = null,
    val grok: GrokQuota? = null,
) {
    fun isStale(nowSec: Long): Boolean = isStale(nowSec, 900L)
    fun isStale(nowSec: Long, maxAgeSec: Long): Boolean = updatedAt > 0 && (nowSec - updatedAt) > maxAgeSec
}

enum class QuotaProblem { TIMEOUT, ERROR, STALE, UNREACHABLE, UNCONFIGURED }

data class ResolvedQuota<T>(
    val live: T?,
    val problem: QuotaProblem?,
    val errorMessage: String?,
    val lastOk: T?,
    val lastOkAtSec: Long?,
    val snapshotUpdatedAtSec: Long? = null,
) {
    val isTimedOut: Boolean get() = problem != null && problem != QuotaProblem.UNCONFIGURED
}

/**
 * Pure resolution rules for AI subscription quotas: distinguishes live readings, immediate backend
 * timeout/network errors, stale snapshots, and unconfigured credentials while preserving the last
 * known successful record and its timestamp.
 */
object AiUsageResolver {
    /** Daemon polls every 60s; >5 minutes without a snapshot update indicates a stalled/unreachable backend. */
    const val STALE_TIMEOUT_SEC = 300L

    private fun statusProblem(status: String): QuotaProblem = when (status.lowercase(Locale.ROOT)) {
        "timeout" -> QuotaProblem.TIMEOUT
        "unconfigured", "unavailable" -> QuotaProblem.UNCONFIGURED
        else -> QuotaProblem.ERROR
    }

    private fun <T> resolveProvider(
        snapshot: AiUsageSnapshot?,
        nowSec: Long,
        provider: T?,
        statusOf: (T) -> String,
        errorOf: (T) -> String?,
        fetchedAtOf: (T) -> Long?,
        lastOkOf: (T) -> T?,
        lastOkAtOf: (T) -> Long?,
        hasData: (T) -> Boolean,
        memLastOk: T?,
        memLastOkAtSec: Long?,
    ): ResolvedQuota<T> {
        val snapTs = snapshot?.updatedAt?.takeIf { it > 0L }
        if (snapshot == null || (snapTs == null && provider == null)) {
            return ResolvedQuota(null, QuotaProblem.UNREACHABLE, null, memLastOk, memLastOkAtSec, snapTs)
        }

        val stale = snapshot.isStale(nowSec, STALE_TIMEOUT_SEC)
        if (provider == null) {
            return ResolvedQuota(
                null,
                if (stale) QuotaProblem.STALE else QuotaProblem.ERROR,
                null,
                memLastOk,
                memLastOkAtSec,
                snapTs,
            )
        }

        val status = statusOf(provider)
        val okNow = status == "ok" && hasData(provider)
        val cachedOk = if (okNow) provider else lastOkOf(provider)?.takeIf(hasData) ?: memLastOk
        val cachedOkAt = if (okNow) {
            lastOkAtOf(provider) ?: fetchedAtOf(provider) ?: snapTs
        } else {
            lastOkAtOf(provider) ?: memLastOkAtSec
        }

        if (stale) {
            return ResolvedQuota(null, QuotaProblem.STALE, errorOf(provider), cachedOk, cachedOkAt, snapTs)
        }
        if (okNow) {
            return ResolvedQuota(provider, null, null, provider, cachedOkAt, snapTs)
        }
        val problem = if (status == "ok") QuotaProblem.ERROR else statusProblem(status)
        return ResolvedQuota(null, problem, errorOf(provider), cachedOk, cachedOkAt, snapTs)
    }

    fun resolveAntigravity(
        snapshot: AiUsageSnapshot?,
        nowSec: Long,
        memLastOk: AntigravityQuota? = null,
        memLastOkAtSec: Long? = null,
    ): ResolvedQuota<AntigravityQuota> = resolveProvider(
        snapshot = snapshot,
        nowSec = nowSec,
        provider = snapshot?.antigravity,
        statusOf = { it.status },
        errorOf = { it.error ?: it.reason },
        fetchedAtOf = { it.fetchedAt },
        lastOkOf = { it.lastOk },
        lastOkAtOf = { it.lastOkAt },
        hasData = { it.geminiWeekly != null || it.gemini5h != null || it.claudeWeekly != null || it.claude5h != null },
        memLastOk = memLastOk,
        memLastOkAtSec = memLastOkAtSec,
    )

    fun resolveCodex(
        snapshot: AiUsageSnapshot?,
        nowSec: Long,
        memLastOk: CodexQuota? = null,
        memLastOkAtSec: Long? = null,
    ): ResolvedQuota<CodexQuota> = resolveProvider(
        snapshot = snapshot,
        nowSec = nowSec,
        provider = snapshot?.codex,
        statusOf = { it.status },
        errorOf = { it.error ?: it.reason },
        fetchedAtOf = { it.fetchedAt },
        lastOkOf = { it.lastOk },
        lastOkAtOf = { it.lastOkAt },
        hasData = { it.windowWeekly != null || it.window5h != null },
        memLastOk = memLastOk,
        memLastOkAtSec = memLastOkAtSec,
    )

    fun resolveGrok(
        snapshot: AiUsageSnapshot?,
        nowSec: Long,
        memLastOk: GrokQuota? = null,
        memLastOkAtSec: Long? = null,
    ): ResolvedQuota<GrokQuota> = resolveProvider(
        snapshot = snapshot,
        nowSec = nowSec,
        provider = snapshot?.grok,
        statusOf = { it.status },
        errorOf = { it.error ?: it.reason },
        fetchedAtOf = { it.fetchedAt },
        lastOkOf = { it.lastOk },
        lastOkAtOf = { it.lastOkAt },
        hasData = { it.cycle != null },
        memLastOk = memLastOk,
        memLastOkAtSec = memLastOkAtSec,
    )

    fun resolveGrokBot(
        snapshot: AiUsageSnapshot?,
        nowSec: Long,
        memLastOk: GrokCycle? = null,
        memLastOkAtSec: Long? = null,
    ): ResolvedQuota<GrokCycle> {
        val snapTs = snapshot?.updatedAt?.takeIf { it > 0L }
        val gk = snapshot?.grok
        val parent = resolveGrok(snapshot, nowSec)
        val buildFallbackOk = gk?.lastOkGrokBuild ?: gk?.lastOk?.grokBuild ?: gk?.lastOk?.lastOkGrokBuild ?: parent.lastOk?.grokBuild ?: memLastOk
        val buildFallbackAt = gk?.grokBuildOkAt ?: gk?.lastOk?.grokBuildOkAt ?: parent.lastOkAtSec?.takeIf { parent.lastOk?.grokBuild != null } ?: memLastOkAtSec

        if (parent.live == null) {
            return ResolvedQuota(
                live = null,
                problem = parent.problem,
                errorMessage = parent.errorMessage,
                lastOk = buildFallbackOk,
                lastOkAtSec = buildFallbackAt,
                snapshotUpdatedAtSec = snapTs,
            )
        }

        val liveGk = parent.live
        val buildStatus = liveGk.grokBuildStatus
        return when {
            buildStatus != null && buildStatus != "ok" -> ResolvedQuota(
                live = null,
                problem = statusProblem(buildStatus),
                errorMessage = liveGk.grokBuildError ?: liveGk.error,
                lastOk = buildFallbackOk,
                lastOkAtSec = buildFallbackAt,
                snapshotUpdatedAtSec = snapTs,
            )
            liveGk.grokBuild != null -> ResolvedQuota(
                live = liveGk.grokBuild,
                problem = null,
                errorMessage = null,
                lastOk = liveGk.grokBuild,
                lastOkAtSec = liveGk.grokBuildOkAt ?: liveGk.lastOkAt ?: liveGk.fetchedAt ?: snapTs,
                snapshotUpdatedAtSec = snapTs,
            )
            // Legacy snapshots without grok_build_status may only provide cycle
            buildStatus == null && liveGk.cycle != null -> ResolvedQuota(
                live = liveGk.cycle,
                problem = null,
                errorMessage = null,
                lastOk = liveGk.cycle,
                lastOkAtSec = liveGk.lastOkAt ?: liveGk.fetchedAt ?: snapTs,
                snapshotUpdatedAtSec = snapTs,
            )
            else -> ResolvedQuota(
                live = null,
                problem = QuotaProblem.ERROR,
                errorMessage = liveGk.grokBuildError,
                lastOk = buildFallbackOk,
                lastOkAtSec = buildFallbackAt,
                snapshotUpdatedAtSec = snapTs,
            )
        }
    }
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
    private val executor = Executors.newSingleThreadExecutor()
    private const val UNREACHABLE_POLL_STREAK = 3
    @Volatile private var lastPollAt = 0L
    @Volatile private var isPolling = false
    @Volatile private var pendingWakeReason: TerminalWakeReason? = null
    @Volatile private var cached: AiUsageSnapshot? = null
    @Volatile private var consecutivePollFailures = 0

    internal var daemonFetcher: () -> AiUsageSnapshot? = ::fetchFromDaemon
    internal var fileReader: (String?) -> AiUsageSnapshot? = ::readFiles
    internal var wallClockMs: () -> Long = System::currentTimeMillis
    internal var pollRunner: (Runnable) -> Unit = { executor.execute(it) }

    @Volatile private var memAntigravity: AntigravityQuota? = null
    @Volatile private var memAntigravityAt: Long? = null
    @Volatile private var memCodex: CodexQuota? = null
    @Volatile private var memCodexAt: Long? = null
    @Volatile private var memGrok: GrokQuota? = null
    @Volatile private var memGrokAt: Long? = null
    @Volatile private var memGrokBuild: GrokCycle? = null
    @Volatile private var memGrokBuildAt: Long? = null

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

    private fun rememberLastOk(snapshot: AiUsageSnapshot) {
        val snapTs = snapshot.updatedAt.takeIf { it > 0L }
        snapshot.antigravity?.let { ag ->
            val ok = if (ag.status == "ok" && (ag.geminiWeekly != null || ag.gemini5h != null || ag.claudeWeekly != null || ag.claude5h != null)) ag else ag.lastOk
            val at = if (ag.status == "ok") (ag.lastOkAt ?: ag.fetchedAt ?: snapTs) else ag.lastOkAt
            if (ok != null) {
                memAntigravity = ok
                if (at != null) memAntigravityAt = at
            }
        }
        snapshot.codex?.let { cx ->
            val ok = if (cx.status == "ok" && (cx.windowWeekly != null || cx.window5h != null)) cx else cx.lastOk
            val at = if (cx.status == "ok") (cx.lastOkAt ?: cx.fetchedAt ?: snapTs) else cx.lastOkAt
            if (ok != null) {
                memCodex = ok
                if (at != null) memCodexAt = at
            }
        }
        snapshot.grok?.let { gk ->
            val ok = if (gk.status == "ok" && gk.cycle != null) gk else gk.lastOk
            val at = if (gk.status == "ok") (gk.lastOkAt ?: gk.fetchedAt ?: snapTs) else gk.lastOkAt
            if (ok != null) {
                memGrok = ok
                if (at != null) memGrokAt = at
            }
            val buildOk = if (gk.status == "ok" && (gk.grokBuildStatus == null || gk.grokBuildStatus == "ok") && gk.grokBuild != null) {
                gk.grokBuild
            } else {
                gk.lastOkGrokBuild ?: gk.lastOk?.grokBuild ?: gk.lastOk?.lastOkGrokBuild
            }
            val buildAt = if (gk.status == "ok" && (gk.grokBuildStatus == null || gk.grokBuildStatus == "ok") && gk.grokBuild != null) {
                gk.grokBuildOkAt ?: gk.lastOkAt ?: gk.fetchedAt ?: snapTs
            } else {
                gk.grokBuildOkAt ?: gk.lastOk?.grokBuildOkAt ?: gk.lastOkAt?.takeIf { gk.lastOk?.grokBuild != null }
            }
            if (buildOk != null) {
                memGrokBuild = buildOk
                if (buildAt != null) memGrokBuildAt = buildAt
            }
        }
    }

    internal fun applyPollResult(snapshot: AiUsageSnapshot?) {
        if (snapshot != null) {
            consecutivePollFailures = 0
            cached = snapshot
            rememberLastOk(snapshot)
            refreshItems()
            return
        }
        val misses = consecutivePollFailures + 1
        consecutivePollFailures = misses
        if (misses >= UNREACHABLE_POLL_STREAK && cached != null) {
            cached = null
            refreshItems()
        }
    }

    private fun triggerPoll(
        force: Boolean = false,
        reason: TerminalWakeReason = TerminalWakeReason.BACKGROUND_POLL,
    ) {
        val elapsedNow = AiUsageTerminal.clockElapsedMs()
        val interval = AiUsageTerminal.pollIntervalMs()
        if (isPolling) {
            if (reason != TerminalWakeReason.BACKGROUND_POLL) {
                pendingWakeReason = reason
            }
            return
        }
        if (!force && lastPollAt != 0L && (elapsedNow - lastPollAt) < interval) return
        isPolling = true
        pollRunner(Runnable {
            var nextReason: TerminalWakeReason? = null
            try {
                val daemonSnap = runCatching { daemonFetcher() }.getOrNull()
                val fileSnap = if (daemonSnap != null && daemonSnap.updatedAt > 0L) {
                    null
                } else {
                    runCatching { fileReader(null) }.getOrNull()
                }
                val nowSec = wallClockMs() / 1000L
                val staleThreshold = if (reason == TerminalWakeReason.MANUAL_RETRY) {
                    TerminalWakePolicy.MANUAL_WAKE_STALE_SEC
                } else {
                    AiUsageResolver.STALE_TIMEOUT_SEC
                }
                val offline = TerminalWakePolicy.isDaemonOffline(daemonSnap, fileSnap, nowSec, staleThreshold)
                val effectiveSnap = when {
                    daemonSnap != null && daemonSnap.updatedAt > 0L -> daemonSnap
                    fileSnap != null -> fileSnap
                    else -> daemonSnap
                }
                applyPollResult(effectiveSnap)
                if (!offline) {
                    val hasValidFresh = effectiveSnap != null &&
                        effectiveSnap.updatedAt > 0L &&
                        !effectiveSnap.isStale(nowSec, AiUsageResolver.STALE_TIMEOUT_SEC)
                    if (AiUsageTerminal.onDaemonReachable(hasValidFresh)) {
                        refreshItems()
                    }
                } else {
                    val cur = cached
                    val statusBarShowingOffline = cur == null ||
                        cur.updatedAt <= 0L ||
                        cur.isStale(nowSec, AiUsageResolver.STALE_TIMEOUT_SEC)
                    val shouldTryWake = reason != TerminalWakeReason.BACKGROUND_POLL || statusBarShowingOffline
                    if (shouldTryWake && AiUsageTerminal.maybeWake(reason)) {
                        refreshItems()
                    }
                }
            } catch (_: Throwable) {
                applyPollResult(null)
            } finally {
                lastPollAt = AiUsageTerminal.clockElapsedMs()
                isPolling = false
                nextReason = pendingWakeReason
                pendingWakeReason = null
            }
            nextReason?.let { triggerPoll(force = true, reason = it) }
        })
    }

    private fun refreshItems() {
        runCatching {
            Ticker.refresh("ai_antigravity")
            Ticker.refresh("ai_codex")
            Ticker.refresh("ai_grok")
            Ticker.refresh("ai_grok_bot")
            Ticker.refresh("ai_usage")
        }
    }

    fun isStartingTerminal(): Boolean = AiUsageTerminal.isBootWatching()

    fun forceRefresh() {
        lastPollAt = 0L
        triggerPoll(force = true, reason = TerminalWakeReason.MANUAL_RETRY)
    }

    fun ensureDaemonReady(reason: TerminalWakeReason) {
        triggerPoll(force = true, reason = reason)
    }

    fun launchTerminalNow(): Boolean {
        val launched = AiUsageTerminal.launchExplicit()
        refreshItems()
        lastPollAt = 0L
        triggerPoll(force = true, reason = TerminalWakeReason.BACKGROUND_POLL)
        return launched
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

        triggerPoll()

        if (cached == null) {
            val fileRes = runCatching { fileReader(null) }.getOrNull()
            if (fileRes != null) {
                cached = fileRes
                rememberLastOk(fileRes)
            }
        }

        return cached
    }

    fun resolveAntigravity(nowMs: Long, customPath: String? = null, staged: AiUsageSnapshot? = null): ResolvedQuota<AntigravityQuota> {
        val snap = staged ?: read(nowMs, customPath)
        val useMem = staged == null && customPath.isNullOrBlank()
        return AiUsageResolver.resolveAntigravity(
            snap, nowMs / 1000L,
            memLastOk = if (useMem) memAntigravity else null,
            memLastOkAtSec = if (useMem) memAntigravityAt else null,
        )
    }

    fun resolveCodex(nowMs: Long, customPath: String? = null, staged: AiUsageSnapshot? = null): ResolvedQuota<CodexQuota> {
        val snap = staged ?: read(nowMs, customPath)
        val useMem = staged == null && customPath.isNullOrBlank()
        return AiUsageResolver.resolveCodex(
            snap, nowMs / 1000L,
            memLastOk = if (useMem) memCodex else null,
            memLastOkAtSec = if (useMem) memCodexAt else null,
        )
    }

    fun resolveGrok(nowMs: Long, customPath: String? = null, staged: AiUsageSnapshot? = null): ResolvedQuota<GrokQuota> {
        val snap = staged ?: read(nowMs, customPath)
        val useMem = staged == null && customPath.isNullOrBlank()
        return AiUsageResolver.resolveGrok(
            snap, nowMs / 1000L,
            memLastOk = if (useMem) memGrok else null,
            memLastOkAtSec = if (useMem) memGrokAt else null,
        )
    }

    fun resolveGrokBot(nowMs: Long, customPath: String? = null, staged: AiUsageSnapshot? = null): ResolvedQuota<GrokCycle> {
        val snap = staged ?: read(nowMs, customPath)
        val useMem = staged == null && customPath.isNullOrBlank()
        return AiUsageResolver.resolveGrokBot(
            snap, nowMs / 1000L,
            memLastOk = if (useMem) memGrokBuild else null,
            memLastOkAtSec = if (useMem) memGrokBuildAt else null,
        )
    }

    internal fun currentSnapshotForTesting(): AiUsageSnapshot? = cached

    /** Clears in-memory cache, primarily for unit testing. */
    fun clearCacheForTesting() {
        lastPollAt = 0L
        isPolling = false
        pendingWakeReason = null
        consecutivePollFailures = 0
        cached = null
        memAntigravity = null
        memAntigravityAt = null
        memCodex = null
        memCodexAt = null
        memGrok = null
        memGrokAt = null
        memGrokBuild = null
        memGrokBuildAt = null
        daemonFetcher = ::fetchFromDaemon
        fileReader = ::readFiles
        wallClockMs = System::currentTimeMillis
        pollRunner = { executor.execute(it) }
        AiUsageTerminal.resetForTesting()
    }
}

@Serializable
data class VmTailnetNode(
    val id: String = "",
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val ip: String = "",
    val os: String = "",
    val online: Boolean = false,
    val mode: String = "",
    val relay: String = "",
)

@Serializable
data class VmTailnetFallback(
    val available: Boolean = false,
    @SerialName("tailnet_name") val tailnetName: String = "",
    @SerialName("total_nodes") val totalNodes: Int = 0,
    @SerialName("online_nodes") val onlineNodes: Int = 0,
    @SerialName("direct_ratio") val directRatio: Double = 0.0,
    @SerialName("primary_derp") val primaryDerp: String = "",
    val nodes: List<VmTailnetNode> = emptyList(),
)

@Serializable
data class VmBuiltInUpdaterState(
    @SerialName("pending_target_version") val pendingTargetVersion: String = "",
    @SerialName("previous_version") val previousVersion: String = "",
    @SerialName("last_check_at") val lastCheckAt: String = "",
    @SerialName("last_error") val lastError: String = "",
    @SerialName("failure_count") val failureCount: Int = 0,
    @SerialName("next_retry_at") val nextRetryAt: String = "",
    val summary: String = "",
)

@Serializable
data class VmNetTransferAgentStatus(
    val installed: Boolean = false,
    @SerialName("binary_exists") val binaryExists: Boolean = false,
    val active: Boolean = false,
    val enabled: Boolean = false,
    val state: String = "unknown",
    val pid: Int = 0,
    val version: String = "",
    val arch: String = "",
    @SerialName("vps_url") val vpsUrl: String = "",
    val registered: Boolean = false,
    @SerialName("device_id") val deviceId: String = "",
    val revoked: Boolean = false,
    @SerialName("has_backup") val hasBackup: Boolean = false,
    @SerialName("latest_backup") val latestBackup: String = "",
    @SerialName("supports_auto_update") val supportsAutoUpdate: Boolean = false,
    @SerialName("built_in_updater") val builtInUpdater: VmBuiltInUpdaterState? = null,
    @SerialName("auto_update_note") val autoUpdateNote: String = "",
    @SerialName("recent_logs") val recentLogs: List<String> = emptyList(),
) {
    val isV040OrNewer: Boolean
        get() = supportsAutoUpdate || isSemverAtLeast(version, 0, 4, 0)

    companion object {
        internal fun isSemverAtLeast(raw: String, targetMajor: Int, targetMinor: Int, targetPatch: Int): Boolean {
            val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore(' ')
            if (clean.isEmpty()) return false
            val parts = clean.split('.')
            if (parts.size < 2) return false
            val maj = parts[0].toIntOrNull() ?: return false
            val min = parts[1].toIntOrNull() ?: return false
            val pat = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                maj != targetMajor -> maj > targetMajor
                min != targetMinor -> min > targetMinor
                else -> pat >= targetPatch
            }
        }
    }
}

@Serializable
data class VmTailnetStatusResponse(
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    val tailnet: VmTailnetFallback = VmTailnetFallback(),
    val agent: VmNetTransferAgentStatus = VmNetTransferAgentStatus(),
)

@Serializable
data class VmNetTransferActionReply(
    val ok: Boolean = false,
    val message: String = "",
    val agent: VmNetTransferAgentStatus? = null,
    @SerialName("current_version") val currentVersion: String = "",
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("has_update") val hasUpdate: Boolean = false,
    val notes: String = "",
    @SerialName("released_at") val releasedAt: String = "",
    val sha256: String = "",
    val size: Long = 0L,
    val url: String = "",
)

@Serializable
private data class VmNetTransferActionRequest(
    val action: String,
    val code: String? = null,
)

/**
 * Localhost / crosvm HTTP bridge to `tools/ai_usage_daemon.py` (`GET /tailnet/status`, `POST /nettransfer/action`).
 * Socket usage stays here alongside [AiUsageReader] to preserve `OneWayOutTest` guarantees.
 */
object TailnetVmBridge {
    private val targets = listOf("127.0.0.1", "10.192.190.115", "10.192.190.62", "localhost")
    private const val PORT = 19876
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    internal fun extractHttpBody(raw: String): Pair<Int, String> {
        val headerSep = raw.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: raw.indexOf("\n\n")
        val statusLine = raw.lineSequence().firstOrNull().orEmpty()
        val statusCode = Regex("^HTTP/\\S+\\s+(\\d{3})").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = if (headerSep >= 0) {
            val offset = if (raw.startsWith("\r\n\r\n", headerSep)) 4 else 2
            raw.substring(headerSep + offset)
        } else {
            raw
        }
        val jsonStart = body.indexOf('{')
        val jsonEnd = body.lastIndexOf('}')
        val cleaned = if (jsonStart >= 0 && jsonEnd >= jsonStart) body.substring(jsonStart, jsonEnd + 1) else body.trim()
        return statusCode to cleaned
    }

    internal fun parseStatus(raw: String): VmTailnetStatusResponse? = runCatching {
        val (status, body) = extractHttpBody(raw)
        if (status != 0 && status !in 200..299) return@runCatching null
        if (!body.startsWith("{")) return@runCatching null
        json.decodeFromString<VmTailnetStatusResponse>(body)
    }.getOrNull()

    internal fun parseActionReply(raw: String): VmNetTransferActionReply? = runCatching {
        val (_, body) = extractHttpBody(raw)
        if (!body.startsWith("{")) return@runCatching null
        json.decodeFromString<VmNetTransferActionReply>(body)
    }.getOrNull()

    fun fetchStatus(readTimeoutMs: Int = 4000): VmTailnetStatusResponse? {
        for (host in targets) {
            val res = runCatching {
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress(host, PORT), 500)
                    socket.soTimeout = readTimeoutMs
                    val out = socket.getOutputStream()
                    out.write("GET /tailnet/status HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseStatus(raw)
                } finally {
                    runCatching { socket.close() }
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    fun postAction(action: String, code: String? = null, timeoutMs: Int = 60_000): VmNetTransferActionReply {
        val payload = json.encodeToString(VmNetTransferActionRequest(action = action, code = code?.trim()?.takeIf { it.isNotEmpty() }))
        val bodyBytes = payload.toByteArray(Charsets.UTF_8)
        for (host in targets) {
            val reply = runCatching {
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress(host, PORT), 500)
                    socket.soTimeout = timeoutMs
                    val req = buildString {
                        append("POST /nettransfer/action HTTP/1.0\r\n")
                        append("Host: localhost\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    val out = socket.getOutputStream()
                    out.write(req)
                    out.write(bodyBytes)
                    out.flush()
                    val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val (status, _) = extractHttpBody(raw)
                    parseActionReply(raw) ?: VmNetTransferActionReply(
                        ok = false,
                        message = "VM 服务返回异常 (HTTP $status)",
                    )
                } finally {
                    runCatching { socket.close() }
                }
            }.getOrNull()
            if (reply != null) return reply
        }
        return VmNetTransferActionReply(
            ok = false,
            message = "无法连接本机 Linux 守护进程 (port $PORT)",
        )
    }
}

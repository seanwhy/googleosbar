package com.meridian11.books.bentobar.items

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Section
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs

/** Why BentoBar is checking whether to start the Googlebook OS Linux Terminal VM. */
enum class TerminalWakeReason {
    /** Periodic poll from the status bar / ticker while AI usage items are active. */
    BACKGROUND_POLL,
    /** User opened BentoBar's settings window (mirrors Antigravity launching Terminal on open). */
    APP_OPEN,
    /** User tapped "立即重试获取" in an AI usage menu while the daemon is offline. */
    MANUAL_RETRY,
    /** User explicitly tapped "启动终端" in an AI usage menu. */
    MANUAL_LAUNCH,
}

/**
 * Pure, unit-tested decision rules for waking the Linux Terminal VM (`com.android.virtualization.terminal`)
 * when the AI usage daemon is unreachable or stale.
 *
 * Guarantees:
 * - Never launches Terminal while the daemon HTTP responder is reachable or a fresh snapshot exists.
 * - Never launches on the lock screen or while the screen is off (and waits for unlock without wasting
 *   an attempt).
 * - Automatically wakes Terminal whenever the status bar hits offline/stale (`TO`) — both on cold start
 *   and whenever a previously running Terminal/VM stops later — with exponential backoff (1m / 3m / 10m / 30m)
 *   if repeated wakes fail to reach the daemon, resetting immediately once the daemon recovers.
 * - Enters a 45 s fast-poll boot-watch window after launch and clears it as soon as valid live data arrives.
 */
class TerminalWakePolicy {
    private var backgroundWakeStreak: Int = 0
    private var terminalAvailable: Boolean = true
    private var hasLaunched: Boolean = false
    private var lastLaunchAtMs: Long = 0L
    private var bootWatchUntilMs: Long = 0L

    @Synchronized
    fun isBootWatching(nowMs: Long): Boolean = bootWatchUntilMs > nowMs

    @Synchronized
    fun pollIntervalMs(nowMs: Long): Long =
        if (isBootWatching(nowMs)) FAST_POLL_INTERVAL_MS else NORMAL_POLL_INTERVAL_MS

    @Synchronized
    fun shouldLaunch(
        reason: TerminalWakeReason,
        nowMs: Long,
        autoStartEnabled: Boolean,
        deviceUnlocked: Boolean,
        daemonOffline: Boolean,
    ): Boolean {
        if (reason == TerminalWakeReason.MANUAL_LAUNCH) return true
        if (!autoStartEnabled || !deviceUnlocked || !daemonOffline || !terminalAvailable) return false
        if (isBootWatching(nowMs)) return false
        return when (reason) {
            TerminalWakeReason.BACKGROUND_POLL -> {
                if (!hasLaunched) true
                else {
                    val requiredGap = BACKGROUND_BACKOFF_MS[backgroundWakeStreak.coerceAtMost(BACKGROUND_BACKOFF_MS.lastIndex)]
                    (nowMs - lastLaunchAtMs) >= requiredGap
                }
            }
            TerminalWakeReason.APP_OPEN -> !hasLaunched || (nowMs - lastLaunchAtMs) >= APP_OPEN_COOLDOWN_MS
            TerminalWakeReason.MANUAL_RETRY -> !hasLaunched || (nowMs - lastLaunchAtMs) >= MANUAL_RETRY_COOLDOWN_MS
            TerminalWakeReason.MANUAL_LAUNCH -> true
        }
    }

    @Synchronized
    fun onLaunchAttempted(reason: TerminalWakeReason, launched: Boolean, nowMs: Long) {
        if (launched) {
            if (reason == TerminalWakeReason.BACKGROUND_POLL) {
                backgroundWakeStreak++
            }
            terminalAvailable = true
            hasLaunched = true
            lastLaunchAtMs = nowMs
            bootWatchUntilMs = nowMs + BOOT_WATCH_DURATION_MS
        } else if (reason != TerminalWakeReason.MANUAL_LAUNCH) {
            terminalAvailable = false
        }
    }

    /** Returns true if this call ended an active boot-watch window. */
    @Synchronized
    fun onDaemonReachable(hasValidSnapshot: Boolean): Boolean {
        if (hasValidSnapshot) {
            backgroundWakeStreak = 0
            if (bootWatchUntilMs != 0L) {
                bootWatchUntilMs = 0L
                return true
            }
        }
        return false
    }

    @Synchronized
    fun reset() {
        backgroundWakeStreak = 0
        terminalAvailable = true
        hasLaunched = false
        lastLaunchAtMs = 0L
        bootWatchUntilMs = 0L
    }

    companion object {
        const val MIN_BACKGROUND_GAP_MS = 60_000L
        /** Backoff schedule for repeated background auto-wakes: 1 min -> 3 min -> 10 min -> 30 min. */
        val BACKGROUND_BACKOFF_MS = longArrayOf(MIN_BACKGROUND_GAP_MS, 180_000L, 600_000L, 1_800_000L)
        const val APP_OPEN_COOLDOWN_MS = 60_000L
        const val MANUAL_RETRY_COOLDOWN_MS = 15_000L
        const val BOOT_WATCH_DURATION_MS = 45_000L
        const val FAST_POLL_INTERVAL_MS = 2_000L
        const val NORMAL_POLL_INTERVAL_MS = 4_000L

        /**
         * Tighter staleness window when the user explicitly taps Retry and HTTP port 19876 is unreachable:
         * the daemon refreshes every 60 s, so >120 s without an update indicates the VM/daemon is down.
         */
        const val MANUAL_WAKE_STALE_SEC = 120L

        fun isDaemonOffline(
            daemonSnap: AiUsageSnapshot?,
            fileSnap: AiUsageSnapshot?,
            nowSec: Long,
            staleThresholdSec: Long = AiUsageResolver.STALE_TIMEOUT_SEC,
        ): Boolean {
            // If the HTTP daemon responded (even while fetching its first snapshot), the VM is already running.
            if (daemonSnap != null) return false
            // If a fresh snapshot file was written recently to shared storage, the daemon is running.
            if (fileSnap != null && fileSnap.updatedAt > 0L && !fileSnap.isStale(nowSec, staleThresholdSec)) {
                return false
            }
            return true
        }
    }
}

/**
 * Pure, unit-tested rules for locating and clicking the Googlebook OS desktop caption bar's
 * Minimize button (`com.android.systemui:id/minimize_window`) for `com.android.virtualization.terminal`
 * without ever clicking Close, Maximize, or another application's window controls.
 */
object TerminalMinimizeRules {
    /** Wait briefly after Terminal's window appears so `MainActivity.onCreate()` finishes `startForegroundService(VmLauncherService)`. */
    const val SETTLE_MS = 800L
    /** Delay between minimize attempts before escalating from `ACTION_CLICK` to a gesture tap. */
    const val RETRY_INTERVAL_MS = 380L
    /** Maximum lifetime of a single auto-minimize session. */
    const val MAX_SESSION_MS = 10_000L
    const val MAX_ATTEMPTS = 4

    enum class WindowKind { APPLICATION, WINDOW_CONTROL, SYSTEM, OTHER }

    data class CaptionNode(
        val ref: Int,
        val id: String,
        val desc: String,
        val text: String,
        val clickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val children: List<CaptionNode> = emptyList(),
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    data class WindowSnapshot(
        val windowId: Int,
        val type: WindowKind,
        val packageName: String,
        val layer: Int,
        val displayId: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val tree: CaptionNode?,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    sealed class Step {
        data object Done : Step()
        data object GiveUp : Step()
        data object Wait : Step()
        data class ClickMinimize(
            val captionWindowId: Int,
            val nodeRef: Int,
            val displayId: Int,
            val centerX: Float,
            val centerY: Float,
            val preferGesture: Boolean,
        ) : Step()
    }

    private val forbiddenTokens = listOf(
        "close", "关闭",
        "maximize", "maximise", "最大化",
        "restore", "还原", "unmaximize",
        "fullscreen", "全屏",
        "menu", "菜单", "expand", "open_menu",
        "split", "分屏", "float", "resize", "caption_handle",
    )

    private val minimizeIdSuffixes = listOf(
        ":id/minimize_window",
        ":id/minimize_button",
        ":id/caption_minimize",
        ":id/btn_minimize",
    )

    private val minimizeTextTokens = listOf(
        "minimize", "minimise", "最小化", "收起窗口",
    )

    private val closeTokens = listOf("close_window", "close", "关闭")
    private val maximizeTokens = listOf("maximize", "maximise", "restore", "最大化", "还原")
    private val terminalNameTokens = listOf("terminal", "终端", "終端")

    private fun norm(s: String): String = s.trim().lowercase(Locale.ROOT)

    private fun isForbidden(node: CaptionNode): Boolean {
        val combined = "${norm(node.id)} ${norm(node.desc)} ${norm(node.text)}"
        return forbiddenTokens.any { it in combined }
    }

    private fun hasMinimizeId(node: CaptionNode): Boolean {
        val id = norm(node.id)
        if (id.isEmpty() || isForbidden(node)) return false
        return minimizeIdSuffixes.any { id.endsWith(it) } || "minimize" in id
    }

    private fun hasMinimizeLabel(node: CaptionNode): Boolean {
        if (isForbidden(node)) return false
        val desc = norm(node.desc)
        val text = norm(node.text)
        return minimizeTextTokens.any { it in desc || it in text }
    }

    private fun mentionsTerminal(root: CaptionNode?): Boolean {
        root ?: return false
        val stack = ArrayDeque<CaptionNode>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val combined = "${norm(n.text)} ${norm(n.desc)}"
            if (terminalNameTokens.any { it in combined }) return true
            for (c in n.children) stack.add(c)
        }
        return false
    }

    private fun alignsWithCaption(caption: WindowSnapshot, term: WindowSnapshot): Boolean {
        if (caption.displayId != term.displayId) return false
        if (caption.layer < term.layer) return false
        if (caption.height !in 16..180) return false
        if (abs(caption.left - term.left) > 32 || abs(caption.right - term.right) > 32) return false
        return caption.top in (term.top - 96)..(term.top + 32)
    }

    private fun findMinimizeNode(
        root: CaptionNode,
        caption: WindowSnapshot,
        restrictTopBand: Boolean,
        allowThreeButtonFallback: Boolean,
    ): CaptionNode? {
        val maxBottom = if (restrictTopBand) caption.top + 180 else Int.MAX_VALUE
        var bestTier = 0
        var bestNode: CaptionNode? = null

        val allNodes = ArrayList<Pair<CaptionNode, CaptionNode?>>()
        fun walk(node: CaptionNode, clickableAncestor: CaptionNode?) {
            val nextAncestor = if (node.clickable && node.enabled && node.visible && node.width in 16..240 && node.height in 16..180) {
                node
            } else {
                clickableAncestor
            }
            if (node.visible && node.enabled && node.width > 0 && node.height > 0 && node.bottom <= maxBottom) {
                allNodes += node to nextAncestor
                val effective = if (node.clickable) node else nextAncestor ?: node
                if (effective.width in 16..240 && effective.height in 16..180 && !isForbidden(effective)) {
                    val tier = when {
                        hasMinimizeId(node) -> 100
                        hasMinimizeLabel(node) -> 80
                        else -> 0
                    }
                    if (tier > bestTier) {
                        bestTier = tier
                        bestNode = effective
                    }
                }
            }
            for (c in node.children) walk(c, nextAncestor)
        }
        walk(root, null)
        if (bestNode != null) return bestNode

        if (!allowThreeButtonFallback) return null

        // Strict fallback for standard 3-button desktop caption group ([minimize][maximize][close]) on trailing edge.
        val midX = caption.left + caption.width / 2
        val trailingButtons = allNodes
            .map { it.first }
            .filter { n ->
                n.clickable && n.enabled && n.visible &&
                    n.width in 20..200 && n.height in 20..160 &&
                    n.left >= midX && n.right <= caption.right + 8
            }
            // Deduplicate nested nodes with identical bounds by keeping the outer clickable container
            .distinctBy { "${it.left},${it.top},${it.right},${it.bottom}" }
            .sortedBy { it.centerX }

        if (trailingButtons.size == 3) {
            val leftBtn = trailingButtons[0]
            val midBtn = trailingButtons[1]
            val rightBtn = trailingButtons[2]
            val rightCombined = "${norm(rightBtn.id)} ${norm(rightBtn.desc)} ${norm(rightBtn.text)}"
            val midCombined = "${norm(midBtn.id)} ${norm(midBtn.desc)} ${norm(midBtn.text)}"
            val rightIsClose = closeTokens.any { it in rightCombined }
            val midIsMax = maximizeTokens.any { it in midCombined }
            if (rightIsClose && midIsMax && !isForbidden(leftBtn)) {
                return leftBtn
            }
        }
        return null
    }

    fun nextStep(
        windows: List<WindowSnapshot>,
        terminalSeenAtMs: Long,
        nowMs: Long,
        attempts: Int,
        lastActionAtMs: Long,
        usedGesture: Boolean,
        startedAtMs: Long,
    ): Step {
        if (nowMs - startedAtMs > MAX_SESSION_MS || attempts >= MAX_ATTEMPTS) {
            return Step.GiveUp
        }

        val termWin = windows
            .filter {
                it.type == WindowKind.APPLICATION &&
                    it.packageName == AiUsageTerminal.TERMINAL_PACKAGE &&
                    it.width > 0 && it.height > 0
            }
            .maxByOrNull { it.layer }

        if (termWin == null) {
            return if (terminalSeenAtMs > 0L) Step.Done else Step.Wait
        }

        if (terminalSeenAtMs <= 0L || (nowMs - terminalSeenAtMs) < SETTLE_MS) {
            return Step.Wait
        }
        if (attempts > 0 && (nowMs - lastActionAtMs) < RETRY_INTERVAL_MS) {
            return Step.Wait
        }

        val controlCandidates = windows
            .filter { it.type == WindowKind.WINDOW_CONTROL && alignsWithCaption(it, termWin) }
            .sortedWith(
                compareBy<WindowSnapshot>(
                    { if (mentionsTerminal(it.tree)) 0 else 1 },
                    { it.layer - termWin.layer },
                )
            )

        val systemCandidates = windows
            .filter { it.type == WindowKind.SYSTEM && alignsWithCaption(it, termWin) }
            .sortedWith(
                compareBy<WindowSnapshot>(
                    { if (mentionsTerminal(it.tree)) 0 else 1 },
                    { it.layer - termWin.layer },
                )
            )

        val ordered = buildList {
            for (c in controlCandidates) add(Triple(c, false, true))
            for (c in systemCandidates) add(Triple(c, false, false))
            add(Triple(termWin, true, false))
        }

        for ((candidate, restrictBand, allowThreeBtn) in ordered) {
            val tree = candidate.tree ?: continue
            val target = findMinimizeNode(
                root = tree,
                caption = candidate,
                restrictTopBand = restrictBand,
                allowThreeButtonFallback = allowThreeBtn,
            ) ?: continue
            // 1st attempt uses clean ACTION_CLICK; if Terminal stayed visible, escalate to gesture tap.
            val preferGesture = attempts >= 1 && (!usedGesture || attempts % 2 == 1)
            return Step.ClickMinimize(
                captionWindowId = candidate.windowId,
                nodeRef = target.ref,
                displayId = candidate.displayId,
                centerX = target.centerX,
                centerY = target.centerY,
                preferGesture = preferGesture,
            )
        }

        return Step.Wait
    }
}

/**
 * Coordinates automatic and manual startup of the Googlebook OS Linux Terminal app
 * (`com.android.virtualization.terminal`) so `ai-usage-daemon.service` boots automatically without
 * requiring the user to launch Antigravity first.
 */
object AiUsageTerminal {
    const val TERMINAL_PACKAGE = "com.android.virtualization.terminal"
    const val TERMINAL_MAIN_ACTIVITY = "com.android.virtualization.terminal.MainActivity"
    const val ACTION_VM_TERMINAL = "android.virtualization.VM_TERMINAL"

    val AI_USAGE_TYPES = setOf("ai_antigravity", "ai_codex", "ai_grok", "ai_grok_bot", "ai_usage")

    private const val KEPT_FOLDER = "ai_usage"
    private const val KEPT_AUTO_START = "auto_start_terminal"
    private const val KEPT_AUTO_MINIMIZE = "auto_minimize_terminal"

    private val policy = TerminalWakePolicy()
    private val _autoStartEnabled = MutableStateFlow(true)
    val autoStartEnabled: StateFlow<Boolean> get() = _autoStartEnabled
    private val _autoMinimizeEnabled = MutableStateFlow(true)
    val autoMinimizeEnabled: StateFlow<Boolean> get() = _autoMinimizeEnabled

    internal var clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
    internal var isDeviceUnlocked: () -> Boolean = ::defaultDeviceUnlocked
    internal var launchImpl: (explicit: Boolean) -> Boolean = ::launchTerminalOnDevice
    internal var minimizeImpl: () -> Boolean = { Env.minimizeTerminalWindow() }

    fun init(context: Context) {
        runCatching {
            val own = Kept.own(KEPT_FOLDER)
            when (own.read(KEPT_AUTO_START)?.text?.trim()) {
                "0" -> _autoStartEnabled.value = false
                "1" -> _autoStartEnabled.value = true
            }
            when (own.read(KEPT_AUTO_MINIMIZE)?.text?.trim()) {
                "0" -> _autoMinimizeEnabled.value = false
                "1" -> _autoMinimizeEnabled.value = true
            }
        }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        if (_autoStartEnabled.value == enabled) return
        _autoStartEnabled.value = enabled
        runCatching {
            Background.executor("ai-terminal").execute {
                runCatching {
                    Kept.own(KEPT_FOLDER).write(KEPT_AUTO_START, if (enabled) "1" else "0", System.currentTimeMillis())
                }
            }
        }
    }

    fun setAutoMinimizeEnabled(enabled: Boolean) {
        if (_autoMinimizeEnabled.value == enabled) return
        _autoMinimizeEnabled.value = enabled
        runCatching {
            Background.executor("ai-terminal").execute {
                runCatching {
                    Kept.own(KEPT_FOLDER).write(KEPT_AUTO_MINIMIZE, if (enabled) "1" else "0", System.currentTimeMillis())
                }
            }
        }
    }

    fun hasAiUsageItems(items: List<ItemConfig>): Boolean =
        items.any { it.type in AI_USAGE_TYPES && it.section != Section.OFF }

    fun isBootWatching(): Boolean = policy.isBootWatching(clockElapsedMs())

    fun pollIntervalMs(): Long = policy.pollIntervalMs(clockElapsedMs())

    fun onDaemonReachable(hasValidSnapshot: Boolean): Boolean = policy.onDaemonReachable(hasValidSnapshot)

    /**
     * Checks [TerminalWakePolicy] and, if permitted, starts the Linux Terminal app to boot the VM.
     * Returns true when a launch succeeded and boot-watch mode was entered.
     */
    @Synchronized
    fun maybeWake(reason: TerminalWakeReason): Boolean {
        val nowMs = clockElapsedMs()
        val allowed = policy.shouldLaunch(
            reason = reason,
            nowMs = nowMs,
            autoStartEnabled = _autoStartEnabled.value,
            deviceUnlocked = isDeviceUnlocked(),
            daemonOffline = true,
        )
        if (!allowed) return false
        val launched = runCatching { launchImpl(reason == TerminalWakeReason.MANUAL_LAUNCH) }.getOrDefault(false)
        policy.onLaunchAttempted(reason, launched, nowMs)
        if (launched && _autoMinimizeEnabled.value) {
            runCatching { minimizeImpl() }
        }
        return launched
    }

    /** Explicit user tap on "启动终端": launches immediately regardless of auto-start preference. */
    @Synchronized
    fun launchExplicit(): Boolean {
        val nowMs = clockElapsedMs()
        val launched = runCatching { launchImpl(true) }.getOrDefault(false)
        policy.onLaunchAttempted(TerminalWakeReason.MANUAL_LAUNCH, launched, nowMs)
        if (launched && _autoMinimizeEnabled.value) {
            runCatching { minimizeImpl() }
        }
        return launched
    }

    internal fun resolveTerminalIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        val pm = context.packageManager

        runCatching { pm.getLaunchIntentForPackage(TERMINAL_PACKAGE) }.getOrNull()?.let { launch ->
            launch.addFlags(flags)
            intents += launch
        }

        val vmTerminal = Intent(ACTION_VM_TERMINAL)
            .setPackage(TERMINAL_PACKAGE)
            .addFlags(flags)
        val explicitMain = Intent(Intent.ACTION_MAIN)
            .setClassName(TERMINAL_PACKAGE, TERMINAL_MAIN_ACTIVITY)
            .addFlags(flags)

        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (isRobolectric) {
            if (intents.isEmpty() && runCatching { pm.resolveActivity(vmTerminal, 0) != null }.getOrDefault(false)) {
                intents += vmTerminal
            }
            if (intents.isEmpty() && runCatching { pm.resolveActivity(explicitMain, 0) != null }.getOrDefault(false)) {
                intents += explicitMain
            }
        } else {
            intents += vmTerminal
            intents += explicitMain
        }
        return intents
    }

    private fun defaultDeviceUnlocked(): Boolean {
        val app = runCatching { Env.app }.getOrNull() ?: return false
        return runCatching {
            val pm = app.getSystemService(PowerManager::class.java)
            val km = app.getSystemService(KeyguardManager::class.java)
            val interactive = pm?.isInteractive ?: true
            val unlocked = km?.isKeyguardLocked != true
            interactive && unlocked
        }.getOrDefault(false)
    }

    private fun launchTerminalOnDevice(explicit: Boolean): Boolean {
        val app = runCatching { Env.app }.getOrNull() ?: return false
        val intents = resolveTerminalIntents(app)
        for (intent in intents) {
            if (Env.launchFromServiceOrApp(intent, quiet = true)) {
                Log.i(Env.TAG, "Launched Linux Terminal ($TERMINAL_PACKAGE) for AI usage daemon (explicit=$explicit)")
                return true
            }
        }
        if (explicit) {
            Background.onMain {
                runCatching {
                    Toast.makeText(app, app.getString(R.string.toast_nothing_can_open), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return false
    }

    internal fun setAutoStartEnabledForTesting(enabled: Boolean) {
        _autoStartEnabled.value = enabled
    }

    internal fun setAutoMinimizeEnabledForTesting(enabled: Boolean) {
        _autoMinimizeEnabled.value = enabled
    }

    internal fun resetForTesting() {
        policy.reset()
        _autoStartEnabled.value = true
        _autoMinimizeEnabled.value = true
        clockElapsedMs = { SystemClock.elapsedRealtime() }
        isDeviceUnlocked = ::defaultDeviceUnlocked
        launchImpl = ::launchTerminalOnDevice
        minimizeImpl = { Env.minimizeTerminalWindow() }
    }
}

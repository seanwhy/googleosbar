package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiUsageTerminalTest {

    @Before
    fun setUp() {
        AiUsageReader.clearCacheForTesting()
    }

    @After
    fun tearDown() {
        AiUsageReader.clearCacheForTesting()
    }

    @Test
    fun isDaemonOfflineDetectsHttpLiveWaitingFreshFileAndStaleFile() {
        val nowSec = 2000L
        val validSnap = AiUsageSnapshot(
            updatedAt = 1990L,
            antigravity = AntigravityQuota(
                status = "ok",
                geminiWeekly = QuotaBucket(usedPercent = 20f, remainingPercent = 80f),
            ),
        )
        val waitingSnap = AiUsageSnapshot(updatedAt = 0L)
        val staleSnap = validSnap.copy(updatedAt = 1600L) // 400s old > 300s
        val mildlyOldSnap = validSnap.copy(updatedAt = 1850L) // 150s old (>120s manual, <=300s auto)

        // HTTP responded -> VM & daemon are alive
        assertFalse(TerminalWakePolicy.isDaemonOffline(daemonSnap = validSnap, fileSnap = null, nowSec = nowSec))
        assertFalse(TerminalWakePolicy.isDaemonOffline(daemonSnap = waitingSnap, fileSnap = null, nowSec = nowSec))
        assertFalse(TerminalWakePolicy.isDaemonOffline(daemonSnap = waitingSnap, fileSnap = staleSnap, nowSec = nowSec))

        // HTTP failed, but fresh file exists -> daemon is alive and writing to shared storage
        assertFalse(TerminalWakePolicy.isDaemonOffline(daemonSnap = null, fileSnap = validSnap, nowSec = nowSec))
        assertFalse(TerminalWakePolicy.isDaemonOffline(daemonSnap = null, fileSnap = mildlyOldSnap, nowSec = nowSec))

        // HTTP failed + file missing, uninitialized, or stale -> daemon is offline
        assertTrue(TerminalWakePolicy.isDaemonOffline(daemonSnap = null, fileSnap = null, nowSec = nowSec))
        assertTrue(TerminalWakePolicy.isDaemonOffline(daemonSnap = null, fileSnap = waitingSnap, nowSec = nowSec))
        assertTrue(TerminalWakePolicy.isDaemonOffline(daemonSnap = null, fileSnap = staleSnap, nowSec = nowSec))

        // Manual retry uses tighter staleness window (120s) when HTTP is down
        assertTrue(
            TerminalWakePolicy.isDaemonOffline(
                daemonSnap = null,
                fileSnap = mildlyOldSnap,
                nowSec = nowSec,
                staleThresholdSec = TerminalWakePolicy.MANUAL_WAKE_STALE_SEC,
            )
        )
    }

    @Test
    fun policyBackgroundWakeWaitsForUnlockAndBacksOffWhenStillOffline() {
        val policy = TerminalWakePolicy()
        var now = 10_000L

        // Device locked at boot: do not launch
        assertFalse(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = now,
                autoStartEnabled = true,
                deviceUnlocked = false,
                daemonOffline = true,
            )
        )

        // Device unlocks: launches immediately
        now += 5_000L // t = 15_000
        assertTrue(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = now,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
        policy.onLaunchAttempted(TerminalWakeReason.BACKGROUND_POLL, launched = true, nowMs = now)
        assertTrue(policy.isBootWatching(now + 1_000L))
        assertEquals(TerminalWakePolicy.FAST_POLL_INTERVAL_MS, policy.pollIntervalMs(now + 1_000L))

        // Never launches again during 45s boot watch
        assertFalse(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = now + 10_000L,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )

        // Right after boot watch expires (50s < 180s backoff): still cooling down so it never spams
        val afterGrace = now + TerminalWakePolicy.BOOT_WATCH_DURATION_MS + 5_000L
        assertFalse(policy.isBootWatching(afterGrace))
        assertEquals(TerminalWakePolicy.NORMAL_POLL_INTERVAL_MS, policy.pollIntervalMs(afterGrace))
        assertFalse(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = afterGrace,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )

        // After 1st retry backoff (3 minutes): retries automatically if status bar is still TO
        val retry1 = now + TerminalWakePolicy.BACKGROUND_BACKOFF_MS[1]
        assertTrue(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = retry1,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
        policy.onLaunchAttempted(TerminalWakeReason.BACKGROUND_POLL, launched = true, nowMs = retry1)

        // 2nd consecutive failed wake backs off to 10 minutes
        assertFalse(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = retry1 + TerminalWakePolicy.BACKGROUND_BACKOFF_MS[1] + 1_000L,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
        assertTrue(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = retry1 + TerminalWakePolicy.BACKGROUND_BACKOFF_MS[2],
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
    }

    @Test
    fun policyAutoWakesAgainWhenPreviouslyConnectedDaemonGoesOffline() {
        val policy = TerminalWakePolicy()
        // Daemon was already running when BentoBar started
        policy.onDaemonReachable(hasValidSnapshot = true)

        // Later Terminal closes / VM stops and status bar hits TO -> background poll MUST auto-wake Terminal!
        assertTrue(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = 100_000L,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
        policy.onLaunchAttempted(TerminalWakeReason.BACKGROUND_POLL, launched = true, nowMs = 100_000L)

        // Daemon recovers -> resets backoff to 0
        policy.onDaemonReachable(hasValidSnapshot = true)

        // Another offline event >= 60s later wakes immediately without waiting for 3-minute backoff
        assertTrue(
            policy.shouldLaunch(
                reason = TerminalWakeReason.BACKGROUND_POLL,
                nowMs = 100_000L + TerminalWakePolicy.MIN_BACKGROUND_GAP_MS,
                autoStartEnabled = true,
                deviceUnlocked = true,
                daemonOffline = true,
            )
        )
    }

    @Test
    fun readerAutoWakesWhenPreviouslyLiveDaemonDropsToTimeoutOnStatusBar() {
        var elapsed = 10_000L
        var wall = 2_000_000L
        var launchCount = 0
        var minimizeCount = 0
        var daemonResponse: AiUsageSnapshot? = AiUsageSnapshot(
            updatedAt = wall / 1000L,
            antigravity = AntigravityQuota(
                status = "ok",
                fetchedAt = wall / 1000L,
                lastOkAt = wall / 1000L,
                geminiWeekly = QuotaBucket(usedPercent = 20f, remainingPercent = 80f),
            ),
        )

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.setAutoMinimizeEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }
        AiUsageTerminal.minimizeImpl = { minimizeCount++; true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { daemonResponse }
        AiUsageReader.fileReader = { null }

        // Initial poll: daemon is live -> no Terminal launch
        AiUsageReader.read(wall)
        assertEquals(0, launchCount)

        // Terminal gets closed: 1st and 2nd transient misses keep cached live quota -> no launch yet
        daemonResponse = null
        elapsed += 5_000L; wall += 5_000L
        AiUsageReader.read(wall)
        assertEquals(0, launchCount)

        elapsed += 5_000L; wall += 5_000L
        AiUsageReader.read(wall)
        assertEquals(0, launchCount)

        // 3rd consecutive miss -> status bar flips to TO (UNREACHABLE) -> auto-launches & minimizes Terminal!
        elapsed += 5_000L; wall += 5_000L
        AiUsageReader.read(wall)
        assertEquals(1, launchCount)
        assertEquals(1, minimizeCount)
        assertTrue(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun policyEnforcesCooldownsForAppOpenAndManualRetry() {
        val policy = TerminalWakePolicy()
        val t0 = 50_000L

        assertTrue(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0, true, true, true))
        policy.onLaunchAttempted(TerminalWakeReason.APP_OPEN, launched = true, nowMs = t0)

        // Daemon connects quickly -> boot watch ends early
        policy.onDaemonReachable(hasValidSnapshot = true)
        assertFalse(policy.isBootWatching(t0 + 5_000L))

        // Within 15s: both APP_OPEN and MANUAL_RETRY respect cooldown
        assertFalse(policy.shouldLaunch(TerminalWakeReason.MANUAL_RETRY, t0 + 10_000L, true, true, true))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0 + 30_000L, true, true, true))

        // At 16s: MANUAL_RETRY allowed, APP_OPEN still cooling down
        assertTrue(policy.shouldLaunch(TerminalWakeReason.MANUAL_RETRY, t0 + 16_000L, true, true, true))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0 + 16_000L, true, true, true))

        // At 61s: APP_OPEN allowed again
        assertTrue(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0 + 61_000L, true, true, true))
    }

    @Test
    fun policyRespectsAutoStartToggleAndManualLaunchOverride() {
        val policy = TerminalWakePolicy()
        val now = 20_000L

        assertFalse(policy.shouldLaunch(TerminalWakeReason.BACKGROUND_POLL, now, autoStartEnabled = false, deviceUnlocked = true, daemonOffline = true))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, now, autoStartEnabled = false, deviceUnlocked = true, daemonOffline = true))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.MANUAL_RETRY, now, autoStartEnabled = false, deviceUnlocked = true, daemonOffline = true))

        // Explicit "启动终端" menu action always works
        assertTrue(policy.shouldLaunch(TerminalWakeReason.MANUAL_LAUNCH, now, autoStartEnabled = false, deviceUnlocked = true, daemonOffline = false))
    }

    @Test
    fun policyStopsAutoRetryingWhenTerminalAppIsMissing() {
        val policy = TerminalWakePolicy()
        val t0 = 10_000L

        assertTrue(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0, true, true, true))
        policy.onLaunchAttempted(TerminalWakeReason.APP_OPEN, launched = false, nowMs = t0)

        assertFalse(policy.isBootWatching(t0 + 1_000L))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.APP_OPEN, t0 + 120_000L, true, true, true))
        assertFalse(policy.shouldLaunch(TerminalWakeReason.MANUAL_RETRY, t0 + 120_000L, true, true, true))
        // Explicit manual launch still tries in case user installed Terminal
        assertTrue(policy.shouldLaunch(TerminalWakeReason.MANUAL_LAUNCH, t0 + 120_000L, true, true, true))
    }

    @Test
    fun hasAiUsageItemsFiltersPlacedUsageTypes() {
        assertFalse(AiUsageTerminal.hasAiUsageItems(emptyList()))
        assertFalse(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("1", "clock", Section.SHOWN))))
        assertFalse(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("1", "ai_antigravity", Section.OFF))))
        assertTrue(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("1", "ai_antigravity", Section.SHOWN))))
        assertTrue(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("2", "ai_codex", Section.HIDDEN))))
        assertTrue(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("3", "ai_grok", Section.SHOWN, whenActive = true))))
        assertTrue(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("4", "ai_grok_bot", Section.SHOWN))))
        assertTrue(AiUsageTerminal.hasAiUsageItems(listOf(ItemConfig("5", "ai_usage", Section.SHOWN))))
    }

    @Test
    fun readerAutoWakesTerminalOnOfflinePollAndClearsBootWatchOnRecovery() {
        var elapsed = 10_000L
        var wall = 2_000_000L
        var launchCount = 0
        var daemonResponse: AiUsageSnapshot? = null
        var fileResponse: AiUsageSnapshot? = AiUsageSnapshot(
            updatedAt = 1_500L, // 500s old -> stale
            antigravity = AntigravityQuota(
                status = "ok",
                geminiWeekly = QuotaBucket(usedPercent = 30f, remainingPercent = 70f),
            ),
        )

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { daemonResponse }
        AiUsageReader.fileReader = { fileResponse }

        // First poll: daemon unreachable & file stale -> wakes Terminal once and enters boot watch
        AiUsageReader.read(wall)
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())

        // Fast poll 2.5s later while still booting: does not launch a second time
        elapsed += 2_500L
        wall += 2_500L
        AiUsageReader.read(wall)
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())

        // Daemon HTTP comes up in "waiting" state (updatedAt=0): still boot watching, no extra launch
        elapsed += 2_500L
        wall += 2_500L
        daemonResponse = AiUsageSnapshot(updatedAt = 0L)
        AiUsageReader.read(wall)
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())

        // Daemon finishes first fetch: boot watch clears immediately and live quota resolves
        elapsed += 2_500L
        wall += 2_500L
        daemonResponse = AiUsageSnapshot(
            updatedAt = wall / 1000L,
            antigravity = AntigravityQuota(
                status = "ok",
                fetchedAt = wall / 1000L,
                lastOkAt = wall / 1000L,
                gemini5h = QuotaBucket(usedPercent = 10f, remainingPercent = 90f),
                geminiWeekly = QuotaBucket(usedPercent = 25f, remainingPercent = 75f),
            ),
        )
        AiUsageReader.read(wall)
        assertFalse(AiUsageReader.isStartingTerminal())
        val resolved = AiUsageReader.resolveAntigravity(wall)
        assertNotNull(resolved.live)
        assertEquals(75f, resolved.live!!.geminiWeekly!!.remaining, 0.01f)
    }

    @Test
    fun readerNeverWakesTerminalWhenFreshFileExistsWithoutHttp() {
        val elapsed = 10_000L
        val wall = 2_000_000L
        var launchCount = 0

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = {
            AiUsageSnapshot(
                updatedAt = (wall / 1000L) - 30L, // fresh 30s ago
                antigravity = AntigravityQuota(
                    status = "ok",
                    geminiWeekly = QuotaBucket(usedPercent = 15f, remainingPercent = 85f),
                ),
            )
        }

        AiUsageReader.read(wall)
        assertEquals(0, launchCount)
        assertFalse(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun forceRefreshAndAppOpenWakeTerminalWhenOffline() {
        var elapsed = 10_000L
        val wall = 2_000_000L
        var launchCount = 0

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        // App open wakes Terminal
        AiUsageReader.ensureDaemonReady(TerminalWakeReason.APP_OPEN)
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())

        // After boot watch expires, tapping Retry wakes Terminal again
        elapsed += TerminalWakePolicy.BOOT_WATCH_DURATION_MS + 1_000L
        AiUsageReader.forceRefresh()
        assertEquals(2, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun manualRetryQueuedDuringInFlightBackgroundPollStillWakesTerminal() {
        val elapsed = 10_000L
        val wall = 2_000_000L
        var launchCount = 0
        val pendingTasks = ArrayDeque<Runnable>()

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }
        // Seed a fresh cached snapshot so a single transient background miss won't wake Terminal yet
        AiUsageReader.applyPollResult(
            AiUsageSnapshot(
                updatedAt = wall / 1000L,
                antigravity = AntigravityQuota(
                    status = "ok",
                    geminiWeekly = QuotaBucket(usedPercent = 20f, remainingPercent = 80f),
                ),
            )
        )
        AiUsageTerminal.onDaemonReachable(hasValidSnapshot = true)

        AiUsageReader.pollRunner = { pendingTasks.addLast(it) }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        // Background poll starts and is in flight
        AiUsageReader.read(wall)
        assertEquals(1, pendingTasks.size)

        // User taps "立即重试获取" while background poll is still running
        AiUsageReader.forceRefresh()
        assertEquals(1, pendingTasks.size) // queued, not overlapping

        // Finish in-flight background poll -> automatically triggers queued MANUAL_RETRY
        pendingTasks.removeFirst().run()
        assertEquals(1, pendingTasks.size)
        assertEquals(0, launchCount)

        // Run queued MANUAL_RETRY -> wakes Terminal!
        pendingTasks.removeFirst().run()
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun launchTerminalNowStartsTerminalEvenWhenAutoStartDisabled() {
        val elapsed = 10_000L
        val wall = 2_000_000L
        var launchCount = 0

        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(false)
        AiUsageTerminal.launchImpl = { launchCount++; true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        assertTrue(AiUsageReader.launchTerminalNow())
        assertEquals(1, launchCount)
        assertTrue(AiUsageReader.isStartingTerminal())
    }

    @Test
    fun itemStateReportsStartingTerminalWhileBootWatching() {
        val elapsed = 10_000L
        val wall = 2_000_000L
        AiUsageTerminal.clockElapsedMs = { elapsed }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { true }

        AiUsageReader.pollRunner = { it.run() }
        AiUsageReader.wallClockMs = { wall }
        AiUsageReader.daemonFetcher = { null }
        AiUsageReader.fileReader = { null }

        val item = ItemConfig("ag-1", "ai_antigravity", Section.SHOWN)
        val state = AntigravityItem.state(item)
        assertEquals(QuotaRingStatus.TIMEOUT, state.quotaRing?.status)
        assertEquals("TO", state.text)
        assertEquals("Antigravity 启动终端中", state.label)
        assertTrue(state.desc, "正在启动终端守护进程" in state.desc)
    }

    // ---- auto-minimize rules & orchestration -----------------------------------------------

    private fun captionNode(
        ref: Int,
        id: String = "",
        desc: String = "",
        text: String = "",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        left: Int = 0,
        top: Int = 0,
        right: Int = 66,
        bottom: Int = 60,
        children: List<TerminalMinimizeRules.CaptionNode> = emptyList(),
    ) = TerminalMinimizeRules.CaptionNode(
        ref = ref,
        id = id,
        desc = desc,
        text = text,
        clickable = clickable,
        enabled = enabled,
        visible = visible,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        children = children,
    )

    private fun desktopCaptionTree(
        appName: String,
        left: Int,
        top: Int,
        right: Int,
        minimizeId: String = "com.android.systemui:id/minimize_window",
        minimizeDesc: String = "Minimize",
        includeMinimize: Boolean = true,
    ): TerminalMinimizeRules.CaptionNode {
        val bottom = top + 60
        val children = mutableListOf(
            captionNode(1, id = "com.android.systemui:id/open_menu_button", desc = appName, left = left + 18, top = top, right = left + 240, bottom = bottom, children = listOf(
                captionNode(2, id = "com.android.systemui:id/application_name", text = appName, clickable = false, left = left + 60, top = top + 12, right = left + 200, bottom = bottom - 12),
            )),
            captionNode(3, id = "com.android.systemui:id/caption_handle", clickable = false, left = left + 240, top = top, right = right - 260, bottom = bottom),
        )
        if (includeMinimize) {
            children += captionNode(4, id = minimizeId, desc = minimizeDesc, left = right - 246, top = top, right = right - 180, bottom = bottom)
        }
        children += captionNode(5, id = "com.android.systemui:id/maximize_window", desc = "Maximize", left = right - 168, top = top, right = right - 102, bottom = bottom)
        children += captionNode(6, id = "com.android.systemui:id/close_window", desc = "Close", left = right - 78, top = top, right = right - 12, bottom = bottom)
        return captionNode(0, id = "com.android.systemui:id/desktop_mode_caption", clickable = false, left = left, top = top, right = right, bottom = bottom, children = children)
    }

    @Test
    fun minimizeRulesWaitsForTerminalThenSettlesAndClicksExactMinimizeNode() {
        val termWin = TerminalMinimizeRules.WindowSnapshot(
            windowId = 24,
            type = TerminalMinimizeRules.WindowKind.APPLICATION,
            packageName = AiUsageTerminal.TERMINAL_PACKAGE,
            layer = 30,
            displayId = 0,
            left = 358,
            top = 170,
            right = 2201,
            bottom = 1322,
            tree = null,
        )
        val termCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 22,
            type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui",
            layer = 31,
            displayId = 0,
            left = 358,
            top = 170,
            right = 2201,
            bottom = 230,
            tree = desktopCaptionTree("Terminal", 358, 170, 2201),
        )

        // 1. Before Terminal appears -> Wait
        val beforeSeen = TerminalMinimizeRules.nextStep(
            windows = emptyList(),
            terminalSeenAtMs = 0L,
            nowMs = 1_000L,
            attempts = 0,
            lastActionAtMs = 0L,
            usedGesture = false,
            startedAtMs = 800L,
        )
        assertEquals(TerminalMinimizeRules.Step.Wait, beforeSeen)

        // 2. Just appeared (200ms < SETTLE_MS) -> Wait so startVm() completes safely
        val settling = TerminalMinimizeRules.nextStep(
            windows = listOf(termCaption, termWin),
            terminalSeenAtMs = 1_000L,
            nowMs = 1_200L,
            attempts = 0,
            lastActionAtMs = 0L,
            usedGesture = false,
            startedAtMs = 800L,
        )
        assertEquals(TerminalMinimizeRules.Step.Wait, settling)

        // 3. Settled (900ms >= SETTLE_MS) -> First attempt uses ACTION_CLICK on node ref 4 (minimize_window)
        val firstClick = TerminalMinimizeRules.nextStep(
            windows = listOf(termCaption, termWin),
            terminalSeenAtMs = 1_000L,
            nowMs = 1_900L,
            attempts = 0,
            lastActionAtMs = 0L,
            usedGesture = false,
            startedAtMs = 800L,
        )
        assertTrue(firstClick is TerminalMinimizeRules.Step.ClickMinimize)
        firstClick as TerminalMinimizeRules.Step.ClickMinimize
        assertEquals(22, firstClick.captionWindowId)
        assertEquals(4, firstClick.nodeRef)
        assertFalse(firstClick.preferGesture)

        // 4. Still visible after ACTION_CLICK + retry delay -> Second attempt upgrades to gesture tap
        val fallbackTap = TerminalMinimizeRules.nextStep(
            windows = listOf(termCaption, termWin),
            terminalSeenAtMs = 1_000L,
            nowMs = 2_400L,
            attempts = 1,
            lastActionAtMs = 1_900L,
            usedGesture = false,
            startedAtMs = 800L,
        )
        assertTrue(fallbackTap is TerminalMinimizeRules.Step.ClickMinimize)
        assertTrue((fallbackTap as TerminalMinimizeRules.Step.ClickMinimize).preferGesture)

        // 5. Terminal window disappeared after minimize -> Done
        val done = TerminalMinimizeRules.nextStep(
            windows = emptyList(),
            terminalSeenAtMs = 1_000L,
            nowMs = 2_600L,
            attempts = 2,
            lastActionAtMs = 2_400L,
            usedGesture = true,
            startedAtMs = 800L,
        )
        assertEquals(TerminalMinimizeRules.Step.Done, done)
    }

    @Test
    fun minimizeRulesDisambiguatesMaximizedTerminalFromMaximizedBackgroundApp() {
        // Both Chrome (behind, layer 20) and Terminal (front, layer 30) are maximized at [0,54][2560,1516]
        val chromeWin = TerminalMinimizeRules.WindowSnapshot(
            windowId = 10, type = TerminalMinimizeRules.WindowKind.APPLICATION,
            packageName = "com.android.chrome", layer = 20, displayId = 0,
            left = 0, top = 54, right = 2560, bottom = 1516, tree = null,
        )
        val chromeCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 11, type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui", layer = 21, displayId = 0,
            left = 0, top = 54, right = 2560, bottom = 114,
            tree = desktopCaptionTree("Chrome", 0, 54, 2560),
        )
        val termWin = TerminalMinimizeRules.WindowSnapshot(
            windowId = 30, type = TerminalMinimizeRules.WindowKind.APPLICATION,
            packageName = AiUsageTerminal.TERMINAL_PACKAGE, layer = 30, displayId = 0,
            left = 0, top = 54, right = 2560, bottom = 1516, tree = null,
        )
        val termCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 31, type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui", layer = 31, displayId = 0,
            left = 0, top = 54, right = 2560, bottom = 114,
            tree = desktopCaptionTree("Terminal", 0, 54, 2560),
        )

        val step = TerminalMinimizeRules.nextStep(
            windows = listOf(chromeCaption, chromeWin, termCaption, termWin),
            terminalSeenAtMs = 1_000L,
            nowMs = 2_000L,
            attempts = 0,
            lastActionAtMs = 0L,
            usedGesture = false,
            startedAtMs = 800L,
        )
        assertTrue(step is TerminalMinimizeRules.Step.ClickMinimize)
        assertEquals(31, (step as TerminalMinimizeRules.Step.ClickMinimize).captionWindowId)
    }

    @Test
    fun minimizeRulesSupportsChineseDescAndThreeButtonFallbackWithoutEverClickingClose() {
        val termWin = TerminalMinimizeRules.WindowSnapshot(
            windowId = 24, type = TerminalMinimizeRules.WindowKind.APPLICATION,
            packageName = AiUsageTerminal.TERMINAL_PACKAGE, layer = 30, displayId = 0,
            left = 200, top = 120, right = 1800, bottom = 1100, tree = null,
        )

        // Chinese content description without English ID
        val zhCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 22, type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui", layer = 31, displayId = 0,
            left = 200, top = 120, right = 1800, bottom = 180,
            tree = desktopCaptionTree("终端", 200, 120, 1800, minimizeId = "", minimizeDesc = "最小化窗口"),
        )
        val zhStep = TerminalMinimizeRules.nextStep(
            windows = listOf(zhCaption, termWin),
            terminalSeenAtMs = 1_000L, nowMs = 2_000L, attempts = 0, lastActionAtMs = 0L, usedGesture = false, startedAtMs = 800L,
        )
        assertTrue(zhStep is TerminalMinimizeRules.Step.ClickMinimize)
        assertEquals(4, (zhStep as TerminalMinimizeRules.Step.ClickMinimize).nodeRef)

        // Unlabeled minimize button in standard 3-button [min][max][close] trailing group
        val unlabeledCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 23, type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui", layer = 31, displayId = 0,
            left = 200, top = 120, right = 1800, bottom = 180,
            tree = desktopCaptionTree("Terminal", 200, 120, 1800, minimizeId = "", minimizeDesc = ""),
        )
        val fallbackStep = TerminalMinimizeRules.nextStep(
            windows = listOf(unlabeledCaption, termWin),
            terminalSeenAtMs = 1_000L, nowMs = 2_000L, attempts = 0, lastActionAtMs = 0L, usedGesture = false, startedAtMs = 800L,
        )
        assertTrue(fallbackStep is TerminalMinimizeRules.Step.ClickMinimize)
        assertEquals(4, (fallbackStep as TerminalMinimizeRules.Step.ClickMinimize).nodeRef)

        // When minimize button is missing (only maximize & close exist): NEVER click close or maximize!
        val noMinCaption = TerminalMinimizeRules.WindowSnapshot(
            windowId = 25, type = TerminalMinimizeRules.WindowKind.WINDOW_CONTROL,
            packageName = "com.android.systemui", layer = 31, displayId = 0,
            left = 200, top = 120, right = 1800, bottom = 180,
            tree = desktopCaptionTree("Terminal", 200, 120, 1800, includeMinimize = false),
        )
        val safeWait = TerminalMinimizeRules.nextStep(
            windows = listOf(noMinCaption, termWin),
            terminalSeenAtMs = 1_000L, nowMs = 2_000L, attempts = 0, lastActionAtMs = 0L, usedGesture = false, startedAtMs = 800L,
        )
        assertEquals(TerminalMinimizeRules.Step.Wait, safeWait)
    }

    @Test
    fun terminalLaunchTriggersAutoMinimizeOnlyWhenEnabledAndLaunchSucceeded() {
        var launchCount = 0
        var minimizeCount = 0
        AiUsageTerminal.clockElapsedMs = { 10_000L }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.setAutoMinimizeEnabledForTesting(true)
        AiUsageTerminal.launchImpl = { launchCount++; true }
        AiUsageTerminal.minimizeImpl = { minimizeCount++; true }

        assertTrue(AiUsageTerminal.maybeWake(TerminalWakeReason.APP_OPEN))
        assertEquals(1, launchCount)
        assertEquals(1, minimizeCount)

        // When auto-minimize is disabled, launch succeeds without calling minimizeImpl
        AiUsageTerminal.resetForTesting()
        AiUsageTerminal.clockElapsedMs = { 10_000L }
        AiUsageTerminal.isDeviceUnlocked = { true }
        AiUsageTerminal.setAutoStartEnabledForTesting(true)
        AiUsageTerminal.setAutoMinimizeEnabledForTesting(false)
        AiUsageTerminal.launchImpl = { launchCount++; true }
        AiUsageTerminal.minimizeImpl = { minimizeCount++; true }

        assertTrue(AiUsageTerminal.maybeWake(TerminalWakeReason.APP_OPEN))
        assertEquals(2, launchCount)
        assertEquals(1, minimizeCount)
    }
}

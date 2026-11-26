package com.meridian11.books.bentobar.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChipRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RemoteKeyboardItem : ItemType(
    "remote_keyboard",
    R.string.item_remote_keyboard_title,
    "brand:remote_keyboard",
    R.string.item_remote_keyboard_desc,
) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 370
    override val canBeActive = true
    override val discreet = true
    override val trigger = Trigger(R.string.trigger_remote_keyboard, R.string.trigger_remote_keyboard_short)

    enum class ChannelKind { PROBING, TAILSCALE, DIRECT, RELAY }

    data class ActiveSession(
        val target: RemoteDeviceSummary,
        val transportMode: String, // "auto" | "tailscale" | "direct" | "relay"
        val customDirectAddress: String = "",
        val startedAtMs: Long = System.currentTimeMillis(),
        val activeChannelLabel: String = "连接中…",
        val channelKind: ChannelKind = ChannelKind.PROBING,
        val verifiedTailscaleBridge: VerifiedTailscaleBridge? = null,
        val consecutiveTailscaleFailures: Int = 0,
        val lastTailscaleProbeMs: Long = 0L,
        val verifiedDirectEndpoint: DirectTargetEndpoint? = null,
        val consecutiveDirectFailures: Int = 0,
        val lastDirectProbeMs: Long = 0L,
        val lastRttMs: Int = 0,
        val sentKeyCount: Long = 0L,
        val ackedKeyCount: Long = 0L,
        val lastKeyLabel: String = "",
        val targetReceiverReady: Boolean = true,
    )

    internal const val REPROBE_INTERVAL_MS = 6_000L
    internal const val MAX_DIRECT_CANDIDATES = 8

    private val deviceExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val probeWorkers = Executors.newCachedThreadPool()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    internal val identityState = MutableStateFlow(NetTransferIdentity())
    internal val bridgeIdentityState = MutableStateFlow<VmKeyboardBridgeHealthResponse?>(null)
    internal val devicesState = MutableStateFlow<List<RemoteDeviceSummary>>(emptyList())
    internal val activeSessionState = MutableStateFlow<ActiveSession?>(null)
    internal val loadingDevicesState = MutableStateFlow(false)
    internal val statusMessageState = MutableStateFlow<String?>(null)
    internal val errorMessageState = MutableStateFlow<String?>(null)

    private val eventSeq = AtomicLong(1L)
    private val pendingEvents = ConcurrentLinkedQueue<RemoteKeyboardEvent>()
    private val senderScheduled = AtomicBoolean(false)
    private val pressedAndroidKeys = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var initialized = false
    @Volatile private var lastDeviceSyncMs = 0L
    @Volatile private var lastEscDownMs = 0L
    @Volatile private var consecutiveEscCount = 0
    @Volatile private var lastHandledEventKey = ""

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            runCatching {
                identityState.value = CloudClipboardStore.loadIdentity()
            }
        }
    }

    override fun onLive() {
        ensureInitialized()
        refreshIdentityFromStore()
        refreshDevices(quiet = true)
    }

    override fun onIdle() {
        if (activeSessionState.value != null) {
            stopMapping(reason = "状态栏已隐藏，自动结束远程键盘映射")
        }
    }

    override fun sample(now: Long) {
        ensureInitialized()
        refreshIdentityFromStore()
        val interval = if (activeSessionState.value != null) 15_000L else 45_000L
        if (now - lastDeviceSyncMs >= interval) {
            lastDeviceSyncMs = now
            refreshDevices(quiet = true)
        }
    }

    fun refreshIdentityFromStore() {
        runCatching {
            val loaded = CloudClipboardStore.loadIdentity()
            if (loaded != identityState.value) {
                identityState.value = loaded
            }
        }
    }

    internal fun activeConfig(): ItemConfig? {
        val cfg = runCatching { Store.config.value }.getOrNull() ?: return null
        return cfg.items.firstOrNull { it.type == type && it.section != Section.OFF }
    }

    internal fun exitShortcutOf(item: ItemConfig?): CloudClipboardShortcut {
        val raw = item?.options?.get("exitShortcut")
        return if (raw.isNullOrBlank()) RemoteKeyboardProtocol.DEFAULT_EXIT_SHORTCUT else CloudClipboardShortcut.parse(raw)
    }

    fun deviceShortcutOptionKey(deviceId: String): String = "shortcut_$deviceId"

    fun deviceShortcutOf(item: ItemConfig?, deviceId: String): CloudClipboardShortcut? {
        if (item == null || deviceId.isBlank()) return null
        val raw = item.options[deviceShortcutOptionKey(deviceId)]
        return CloudClipboardShortcut.parseOrNull(raw)
    }

    internal fun isTripleEscEnabled(item: ItemConfig?): Boolean =
        item?.optBool("tripleEscExit", true) ?: true

    internal fun transportModeOf(item: ItemConfig?): String {
        val raw = item?.opt("transportMode", RemoteKeyboardProtocol.MODE_AUTO)?.lowercase() ?: RemoteKeyboardProtocol.MODE_AUTO
        return when (raw) {
            RemoteKeyboardProtocol.MODE_TAILSCALE,
            RemoteKeyboardProtocol.MODE_RELAY,
            RemoteKeyboardProtocol.MODE_DIRECT,
            -> raw
            else -> RemoteKeyboardProtocol.MODE_AUTO
        }
    }

    internal fun customDirectAddressOf(item: ItemConfig?): String =
        item?.opt("directAddress", "")?.trim().orEmpty()

    internal fun selectedTargetIdOf(item: ItemConfig?): String =
        item?.opt("targetDeviceId", "")?.trim().orEmpty()

    internal fun menuTargetsFor(
        devices: List<RemoteDeviceSummary>,
        activeTargetId: String? = null,
    ): List<RemoteDeviceSummary> =
        devices.filter { it.isKeyboardMenuVisible || (activeTargetId != null && it.id == activeTargetId) }

    internal fun shortcutEligibleTargetsFor(
        item: ItemConfig?,
        devices: List<RemoteDeviceSummary>,
    ): List<RemoteDeviceSummary> =
        devices.filter { it.isKeyboardCapable || deviceShortcutOf(item, it.id) != null }

    internal fun selectedTargetFor(item: ItemConfig?, devices: List<RemoteDeviceSummary>): RemoteDeviceSummary? {
        val eligible = menuTargetsFor(devices)
        val wantedId = selectedTargetIdOf(item)
        if (wantedId.isNotEmpty()) {
            eligible.firstOrNull { it.id == wantedId }?.let { return it }
        }
        return eligible.firstOrNull { it.keyboardActive } ?: eligible.firstOrNull()
    }

    val isMappingActive: Boolean
        get() = activeSessionState.value != null

    /**
     * Left-clicking the status bar item:
     * - When mapping is active and `clickToStop` is enabled (default true): immediately stops mapping
     *   and restores local keyboard input with a single mouse click.
     * - When mapping is inactive and `clickToStart` is enabled: immediately starts mapping to the
     *   selected target computer.
     */
    override fun onClick(item: ItemConfig): Boolean {
        ensureInitialized()
        if (activeSessionState.value != null && item.optBool("clickToStop", true)) {
            stopMapping(reason = "已通过状态栏点击关闭远程键盘映射")
            return true
        }
        if (activeSessionState.value == null && item.optBool("clickToStart", false)) {
            val target = selectedTargetFor(item, devicesState.value)
            if (target != null) {
                startMapping(target, item)
                return true
            }
        }
        return false
    }

    /**
     * Intercepts physical keystrokes from `BarService.onKeyEvent(event)`:
     * - When mapping is inactive: checks if the pressed shortcut matches any target device's
     *   configured activation shortcut (e.g. `Alt + 1`, `Ctrl + Alt + 1`), instantly activating
     *   remote mapping to that device.
     * - When mapping is active: returns `true` for all keystrokes to prevent local input and
     *   forward them to the remote computer.
     * - Checks for the exit shortcut (default `Ctrl + Alt + Q`) or rapid `Esc x 3` to exit mapping
     *   and release any held keys on the remote computer.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val session = activeSessionState.value
        if (session == null) {
            // When mapping is inactive, check if any desktop target's configured shortcut was pressed
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val cfg = activeConfig()
                val devices = devicesState.value
                for (dev in devices) {
                    if (!dev.isKeyboardCapable) continue
                    val sc = deviceShortcutOf(cfg, dev.id) ?: continue
                    if (sc.matches(event)) {
                        if (!dev.online) {
                            toastOnMain("${dev.displayTitle} 当前离线，无法开启键盘映射")
                            return true
                        }
                        startMapping(dev, cfg?.with("targetDeviceId", dev.id))
                        return true
                    }
                }
            }
            return false
        }

        if (event.eventTime > 0L) {
            val dedupKey = "${event.action}:${event.keyCode}:${event.eventTime}:${event.repeatCount}"
            if (dedupKey == lastHandledEventKey) return true
            lastHandledEventKey = dedupKey
        }
        val cfg = activeConfig()
        val exitShortcut = exitShortcutOf(cfg)

        if (event.action == KeyEvent.ACTION_DOWN) {
            // 1. Check primary exit shortcut (e.g. Ctrl + Alt + Q)
            if (exitShortcut.matches(event)) {
                val label = exitShortcut.format()
                stopMapping(reason = "已按退出组合键 ($label)，恢复本机键盘输入")
                return true
            }

            // 2. Check emergency triple-Esc exit (3x Esc within 900 ms)
            if (isTripleEscEnabled(cfg) &&
                event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                event.repeatCount == 0 &&
                !event.isCtrlPressed &&
                !event.isAltPressed &&
                !event.isShiftPressed &&
                !event.isMetaPressed
            ) {
                val now = System.currentTimeMillis()
                if (now - lastEscDownMs <= 650L) {
                    consecutiveEscCount += 1
                } else {
                    consecutiveEscCount = 1
                }
                lastEscDownMs = now
                if (consecutiveEscCount >= 3) {
                    consecutiveEscCount = 0
                    stopMapping(reason = "已连按 3 次 Esc，恢复本机键盘输入")
                    return true
                }
            } else if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) {
                consecutiveEscCount = 0
            }

            pressedAndroidKeys.add(event.keyCode)
        } else if (event.action == KeyEvent.ACTION_UP) {
            pressedAndroidKeys.remove(event.keyCode)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                enqueueEvent(
                    RemoteKeyboardEvent(
                        seq = eventSeq.getAndIncrement(),
                        action = "text",
                        text = chars,
                        keyName = "Text(${chars.length})",
                        sentAtMs = System.currentTimeMillis(),
                    ),
                    session,
                )
            }
            return true
        } else {
            return true
        }

        // Convert and queue for remote transmission
        val seq = eventSeq.getAndIncrement()
        val protoEvent = RemoteKeyboardKeyMapper.toProtocolEvent(event, seq)
        if (protoEvent != null) {
            enqueueEvent(protoEvent, session)
        }

        // Always consume the key event while mapping is active so local apps receive nothing
        return true
    }

    fun startMapping(target: RemoteDeviceSummary, item: ItemConfig? = activeConfig()) {
        ensureInitialized()
        refreshIdentityFromStore()
        val id = identityState.value
        val mode = transportModeOf(item)
        val customAddr = customDirectAddressOf(item)
        RemoteKeyboardProtocol.invalidateLocalSubnetCache()
        val bridgeReady = bridgeIdentityState.value?.identityReady == true || devicesState.value.isNotEmpty()
        if (mode != RemoteKeyboardProtocol.MODE_DIRECT &&
            mode != RemoteKeyboardProtocol.MODE_TAILSCALE &&
            !id.isRegistered &&
            id.sessionCookie.isBlank() &&
            !bridgeReady
        ) {
            errorMessageState.value = "请先在设置或云剪切板中完成 VPS 设备注册，或确认 Debian VM 桥接在线。"
            toastOnMain("请先注册 VPS 设备凭证")
            return
        }

        // If switching targets while active, release keys on the previous target first
        if (activeSessionState.value != null) {
            stopMapping(reason = null, silent = true)
        }

        pressedAndroidKeys.clear()
        pendingEvents.clear()
        consecutiveEscCount = 0

        val (initialKind, initialChannel) = when (mode) {
            RemoteKeyboardProtocol.MODE_TAILSCALE -> ChannelKind.PROBING to "Tailscale 探测中…"
            RemoteKeyboardProtocol.MODE_DIRECT -> ChannelKind.PROBING to "LAN 直连探测中…"
            RemoteKeyboardProtocol.MODE_RELAY -> ChannelKind.RELAY to "VPS 中转"
            else -> ChannelKind.PROBING to "TS 优先探测中…"
        }

        val nowMs = System.currentTimeMillis()
        val session = ActiveSession(
            target = target,
            transportMode = mode,
            customDirectAddress = customAddr,
            startedAtMs = nowMs,
            activeChannelLabel = initialChannel,
            channelKind = initialKind,
            lastTailscaleProbeMs = nowMs,
            lastDirectProbeMs = nowMs,
            targetReceiverReady = target.keyboardActive,
        )
        activeSessionState.value = session
        errorMessageState.value = null
        val exitHint = exitShortcutOf(item).format()
        statusMessageState.value = "正在控制 ${target.displayTitle} · 按 $exitHint 退出"
        Ticker.refresh(type)

        // Probe Tailscale VM bridge & direct LAN endpoints in background unless forced RELAY
        if (mode != RemoteKeyboardProtocol.MODE_RELAY) {
            probeDirectEndpointsAsync(session)
        }

        toastOnMain("已开启远程键盘映射 → ${target.displayTitle} (按 $exitHint 或点状态栏退出)")
    }

    fun stopMapping(reason: String? = "已停止远程键盘映射", silent: Boolean = false) {
        val prev = activeSessionState.value ?: return
        activeSessionState.value = null
        pressedAndroidKeys.clear()
        pendingEvents.clear()
        consecutiveEscCount = 0

        if (!reason.isNullOrBlank()) {
            statusMessageState.value = reason
            if (!silent) {
                toastOnMain(reason)
            }
        }
        Ticker.refresh(type)

        // Always send a release_all event so the remote PC never keeps Ctrl/Alt/Shift stuck down
        val releaseEvent = RemoteKeyboardKeyMapper.createReleaseAllEvent(eventSeq.getAndIncrement())
        sendExecutor.execute {
            runCatching {
                dispatchBatch(prev, listOf(releaseEvent))
            }
            RemoteKeyboardClient.closeConnections()
            VmKeyboardBridgeClient.closeConnections()
        }
    }

    private fun updateActiveSession(sessionId: Long, transform: (ActiveSession) -> ActiveSession) {
        activeSessionState.update { cur ->
            if (cur != null && cur.startedAtMs == sessionId) transform(cur) else cur
        }
    }

    internal fun directCandidatesFor(session: ActiveSession): List<DirectTargetEndpoint> {
        val out = ArrayList<DirectTargetEndpoint>()
        val seen = HashSet<String>()
        val defaultPort = session.target.directPort.takeIf { it in 1..65535 }
            ?: RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT
        fun addUnique(ep: DirectTargetEndpoint?) {
            if (ep != null && seen.add("${ep.host}:${ep.port}")) {
                out.add(ep)
            }
        }
        addUnique(session.verifiedDirectEndpoint)
        addUnique(RemoteKeyboardProtocol.parseDirectEndpoint(session.customDirectAddress, defaultPort))
        val localSubnets = RemoteKeyboardProtocol.currentLocalLanSubnets()
        for (ep in session.target.candidateDirectEndpointsFor(localSubnets)) {
            addUnique(ep)
        }
        return out
    }

    internal fun tailscaleCandidatesFor(session: ActiveSession): List<DirectTargetEndpoint> {
        val out = ArrayList<DirectTargetEndpoint>()
        val seen = HashSet<String>()
        val defaultPort = session.target.directPort.takeIf { it in 1..65535 }
            ?: RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT
        fun addIfTailscale(ep: DirectTargetEndpoint?) {
            if (ep != null && RemoteKeyboardProtocol.isTailscaleIpv4(ep.host) && seen.add("${ep.host}:${ep.port}")) {
                out.add(ep)
            }
        }
        addIfTailscale(session.verifiedTailscaleBridge?.targetEndpoint)
        addIfTailscale(RemoteKeyboardProtocol.parseDirectEndpoint(session.customDirectAddress, defaultPort))
        for (ep in session.target.candidateTailscaleEndpoints()) {
            addIfTailscale(ep)
        }
        return out
    }

    internal fun formatTailscaleChannelLabel(bridge: VerifiedTailscaleBridge): String {
        val modeTag = when {
            bridge.direct -> "TS 直连"
            bridge.relay.isNotBlank() -> "TS DERP(${bridge.relay})"
            else -> "TS 通道"
        }
        val rttSuffix = if (bridge.rttMs > 0) " · ${bridge.rttMs}ms" else ""
        return "$modeTag (${bridge.targetEndpoint.host}:${bridge.targetEndpoint.port}$rttSuffix)"
    }

    internal fun formatDirectChannelLabel(ep: DirectTargetEndpoint, rttMs: Int): String {
        val kind = if (RemoteKeyboardProtocol.isTailscaleIpv4(ep.host)) "TS 直连" else "LAN 直连"
        val rttSuffix = if (rttMs > 0) " · ${rttMs}ms" else ""
        return "$kind (${ep.host}:${ep.port}$rttSuffix)"
    }

    internal fun shortChannelBadge(session: ActiveSession): String {
        val rttPart = if (session.lastRttMs > 0) " · ${session.lastRttMs}ms" else ""
        return when (session.channelKind) {
            ChannelKind.TAILSCALE -> {
                val bridge = session.verifiedTailscaleBridge
                val prefix = if (bridge != null && !bridge.direct && bridge.relay.isNotBlank()) {
                    "TS DERP"
                } else {
                    "TS 直连"
                }
                "$prefix$rttPart"
            }
            ChannelKind.DIRECT -> "LAN 直连$rttPart"
            ChannelKind.RELAY -> "VPS 中转$rttPart"
            ChannelKind.PROBING -> "探测中…"
        }
    }

    private fun probeDirectEndpointsAsync(session: ActiveSession) {
        val sessionId = session.startedAtMs
        probeExecutor.execute {
            if (activeSessionState.value?.startedAtMs != sessionId) return@execute

            val mode = session.transportMode
            val tsCandidates = tailscaleCandidatesFor(session)

            // 1. Tailscale VM bridge first in MODE_TAILSCALE and MODE_AUTO
            if (mode == RemoteKeyboardProtocol.MODE_TAILSCALE || mode == RemoteKeyboardProtocol.MODE_AUTO) {
                if (tsCandidates.isEmpty()) {
                    if (mode == RemoteKeyboardProtocol.MODE_TAILSCALE) {
                        updateActiveSession(sessionId) { cur ->
                            cur.copy(
                                verifiedTailscaleBridge = null,
                                verifiedDirectEndpoint = null,
                                channelKind = ChannelKind.PROBING,
                                activeChannelLabel = "未发现 Tailscale IP",
                            )
                        }
                        errorMessageState.value = "未发现 ${session.target.displayTitle} 的 Tailscale IP (100.64.0.0/10)，请确认对端在线或在下方填写 100.x 地址"
                        Ticker.refresh(type)
                        return@execute
                    }
                } else {
                    val outcome = VmKeyboardBridgeClient.probeTailscaleCandidates(
                        candidates = tsCandidates,
                        executor = probeWorkers,
                        expectedDeviceId = session.target.id,
                    )
                    if (activeSessionState.value?.startedAtMs != sessionId) return@execute
                    when (outcome) {
                        is TailscaleBridgeProbeOutcome.Connected -> {
                            val bridge = outcome.bridge
                            updateActiveSession(sessionId) { cur ->
                                cur.copy(
                                    verifiedTailscaleBridge = bridge,
                                    channelKind = ChannelKind.TAILSCALE,
                                    activeChannelLabel = formatTailscaleChannelLabel(bridge),
                                    consecutiveTailscaleFailures = 0,
                                    lastRttMs = bridge.rttMs,
                                    targetReceiverReady = true,
                                )
                            }
                            errorMessageState.value = null
                            Ticker.refresh(type)
                            return@execute
                        }
                        is TailscaleBridgeProbeOutcome.BridgeUnreachable -> {
                            // If Android itself runs Tailscale VPN (no VM bridge), still allow direct 100.x:19878
                            if (mode == RemoteKeyboardProtocol.MODE_TAILSCALE) {
                                val t0 = System.nanoTime()
                                val matchedTs = RemoteKeyboardClient.probeBestDirectEndpoint(
                                    candidates = tsCandidates,
                                    executor = probeWorkers,
                                    expectedDeviceId = session.target.id,
                                )
                                if (activeSessionState.value?.startedAtMs != sessionId) return@execute
                                if (matchedTs != null) {
                                    val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                                    updateActiveSession(sessionId) { cur ->
                                        cur.copy(
                                            verifiedTailscaleBridge = null,
                                            verifiedDirectEndpoint = matchedTs,
                                            channelKind = ChannelKind.TAILSCALE,
                                            activeChannelLabel = formatDirectChannelLabel(matchedTs, rtt),
                                            lastRttMs = rtt,
                                            consecutiveDirectFailures = 0,
                                            targetReceiverReady = true,
                                        )
                                    }
                                    errorMessageState.value = null
                                    Ticker.refresh(type)
                                    return@execute
                                }
                                val primary = tsCandidates.first()
                                updateActiveSession(sessionId) { cur ->
                                    cur.copy(
                                        verifiedTailscaleBridge = null,
                                        verifiedDirectEndpoint = null,
                                        channelKind = ChannelKind.PROBING,
                                        activeChannelLabel = "TS 桥接未启动 (${primary.host})",
                                    )
                                }
                                errorMessageState.value = "Tailscale 桥接未连接 (VM :${VmKeyboardBridgeClient.BRIDGE_PORT} 未响应) 且本机直连 ${primary.host}:${primary.port} 不通"
                                Ticker.refresh(type)
                                return@execute
                            }
                        }
                        is TailscaleBridgeProbeOutcome.TargetUnreachable -> {
                            if (mode == RemoteKeyboardProtocol.MODE_TAILSCALE) {
                                updateActiveSession(sessionId) { cur ->
                                    cur.copy(
                                        verifiedTailscaleBridge = null,
                                        verifiedDirectEndpoint = null,
                                        channelKind = ChannelKind.PROBING,
                                        activeChannelLabel = "TS 目标未通 (${outcome.endpoint.host})",
                                    )
                                }
                                errorMessageState.value = "强制 Tailscale 失败: ${outcome.endpoint.host}:${outcome.endpoint.port} (${outcome.detail})"
                                Ticker.refresh(type)
                                return@execute
                            }
                        }
                    }
                }
            }

            // 2. Direct LAN / P2P probing (raised candidate cap from 4 to 8)
            val candidates = directCandidatesFor(session).take(MAX_DIRECT_CANDIDATES)
            if (activeSessionState.value?.startedAtMs != sessionId) return@execute
            val directT0 = System.nanoTime()
            val matched = RemoteKeyboardClient.probeBestDirectEndpoint(
                candidates = candidates,
                executor = probeWorkers,
                expectedDeviceId = session.target.id,
            )
            if (activeSessionState.value?.startedAtMs != sessionId) return@execute
            if (matched != null) {
                val rtt = ((System.nanoTime() - directT0) / 1_000_000L).toInt().coerceAtLeast(1)
                val kind = if (RemoteKeyboardProtocol.isTailscaleIpv4(matched.host)) {
                    ChannelKind.TAILSCALE
                } else {
                    ChannelKind.DIRECT
                }
                updateActiveSession(sessionId) { cur ->
                    cur.copy(
                        verifiedDirectEndpoint = matched,
                        channelKind = kind,
                        activeChannelLabel = formatDirectChannelLabel(matched, rtt),
                        lastRttMs = rtt,
                        consecutiveDirectFailures = 0,
                        targetReceiverReady = true,
                    )
                }
                errorMessageState.value = null
                Ticker.refresh(type)
                return@execute
            }

            // 3. Fallback when neither Tailscale nor LAN Direct connected
            val current = activeSessionState.value ?: return@execute
            if (current.startedAtMs == sessionId &&
                current.verifiedTailscaleBridge == null &&
                current.verifiedDirectEndpoint == null
            ) {
                if (current.transportMode == RemoteKeyboardProtocol.MODE_DIRECT) {
                    val primaryHint = candidates.firstOrNull()?.let { "${it.host}:${it.port}" } ?: "19878"
                    updateActiveSession(sessionId) { cur ->
                        if (cur.verifiedDirectEndpoint != null) cur
                        else cur.copy(
                            channelKind = ChannelKind.PROBING,
                            activeChannelLabel = "直连未通 ($primaryHint)",
                        )
                    }
                    errorMessageState.value = "直连探测未通 ($primaryHint)：请检查目标端口、防火墙或同一局域网连接"
                } else {
                    updateActiveSession(sessionId) { cur ->
                        if (cur.verifiedTailscaleBridge != null || cur.verifiedDirectEndpoint != null) cur
                        else cur.copy(
                            channelKind = ChannelKind.RELAY,
                            activeChannelLabel = "VPS 中转",
                        )
                    }
                }
                Ticker.refresh(type)
            }
        }
    }

    private fun enqueueEvent(event: RemoteKeyboardEvent, session: ActiveSession) {
        pendingEvents.add(event)
        updateActiveSession(session.startedAtMs) { cur ->
            val arrow = when (event.action) {
                "down" -> "↓"
                "up" -> "↑"
                else -> "文本"
            }
            cur.copy(
                sentKeyCount = cur.sentKeyCount + 1L,
                lastKeyLabel = "${event.keyName} ($arrow)",
            )
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!senderScheduled.compareAndSet(false, true)) return
        sendExecutor.execute {
            try {
                while (true) {
                    val batch = ArrayList<RemoteKeyboardEvent>(16)
                    while (batch.size < 32) {
                        val next = pendingEvents.poll() ?: break
                        batch.add(next)
                    }
                    if (batch.isEmpty()) break
                    val session = activeSessionState.value ?: break
                    dispatchBatch(session, batch)
                }
            } finally {
                senderScheduled.set(false)
                if (pendingEvents.isNotEmpty() && activeSessionState.value != null) {
                    scheduleFlush()
                }
            }
        }
    }

    private fun sendRelayWithRetry(
        identity: NetTransferIdentity,
        request: RemoteKeyboardSendRequest,
        sessionId: Long,
        allowAbortCheck: Boolean = true,
    ): RemoteKeyboardSendResponse {
        var lastErr: Throwable? = null
        val delays = longArrayOf(80L, 200L)
        for (attempt in 0..delays.size) {
            if (allowAbortCheck && attempt > 0 && activeSessionState.value?.startedAtMs != sessionId) {
                break
            }
            try {
                return if (identity.isRegistered || identity.sessionCookie.isNotBlank()) {
                    RemoteKeyboardClient.sendRelay(identity, request)
                } else {
                    VmKeyboardBridgeClient.sendRelayViaBridge(request, deviceExecutor)
                }
            } catch (t: Throwable) {
                lastErr = t
                if (attempt < delays.size) {
                    try {
                        Thread.sleep(delays[attempt])
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw lastErr ?: IllegalStateException("VPS 转发失败")
    }

    private fun dispatchBatch(session: ActiveSession, batch: List<RemoteKeyboardEvent>) {
        if (batch.isEmpty()) return
        val sessionId = session.startedAtMs
        val currentSession = activeSessionState.value?.takeIf { it.startedAtMs == sessionId } ?: session
        val isStoppingReleaseAll = batch.size == 1 && batch[0].action == "release_all" && activeSessionState.value == null
        val id = identityState.value
        val bridgeInfo = bridgeIdentityState.value
        val effectiveSourceId = id.deviceId.ifBlank { bridgeInfo?.selfDeviceId.orEmpty() }
        val effectiveSourceName = id.displayName.ifBlank {
            bridgeInfo?.selfDisplayName?.ifBlank { "googlebook" } ?: "googlebook"
        }
        val req = RemoteKeyboardSendRequest(
            targetDeviceId = currentSession.target.id,
            sourceDeviceId = effectiveSourceId,
            sourceName = effectiveSourceName,
            events = batch,
        )
        val payloadJson = CloudClipboardProtocol.json.encodeToString(req)
        val payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)

        // 0. Forced Tailscale path (never falls back silently to VPS relay)
        if (currentSession.transportMode == RemoteKeyboardProtocol.MODE_TAILSCALE) {
            val tsCandidates = tailscaleCandidatesFor(currentSession)
            if (tsCandidates.isEmpty()) {
                errorMessageState.value = "未发现目标设备的 Tailscale IP (100.64.0.0/10)，请确认对端运行 Tailscale 或切换为自动模式。"
                Ticker.refresh(type)
                return
            }

            var activeBridge = currentSession.verifiedTailscaleBridge
            if (activeBridge != null) {
                val fwd = VmKeyboardBridgeClient.sendForwardOverBridge(activeBridge, payloadJson)
                if (fwd.ok) {
                    val updatedBridge = activeBridge.copy(rttMs = fwd.rttMs)
                    val acked = fwd.accepted.takeIf { it > 0 } ?: batch.size
                    updateActiveSession(sessionId) { cur ->
                        val switched = cur.channelKind != ChannelKind.TAILSCALE || cur.lastRttMs != fwd.rttMs
                        cur.copy(
                            verifiedTailscaleBridge = updatedBridge,
                            ackedKeyCount = cur.ackedKeyCount + acked,
                            lastRttMs = fwd.rttMs,
                            channelKind = ChannelKind.TAILSCALE,
                            activeChannelLabel = formatTailscaleChannelLabel(updatedBridge),
                            consecutiveTailscaleFailures = 0,
                        ).also { if (switched) Ticker.refresh(type) }
                    }
                    errorMessageState.value = null
                    return
                }
                activeBridge = null
                updateActiveSession(sessionId) { cur ->
                    cur.copy(verifiedTailscaleBridge = null, consecutiveTailscaleFailures = 0)
                }
            }

            // Re-establish via VM bridge (or direct Android Tailscale if bridge unavailable)
            if (!isStoppingReleaseAll) {
                when (val outcome = VmKeyboardBridgeClient.probeTailscaleCandidates(
                    candidates = tsCandidates,
                    executor = probeWorkers,
                    expectedDeviceId = currentSession.target.id,
                )) {
                    is TailscaleBridgeProbeOutcome.Connected -> {
                        val fwd = VmKeyboardBridgeClient.sendForwardOverBridge(outcome.bridge, payloadJson)
                        if (fwd.ok) {
                            val updatedBridge = outcome.bridge.copy(rttMs = fwd.rttMs)
                            val acked = fwd.accepted.takeIf { it > 0 } ?: batch.size
                            updateActiveSession(sessionId) { cur ->
                                cur.copy(
                                    verifiedTailscaleBridge = updatedBridge,
                                    verifiedDirectEndpoint = null,
                                    ackedKeyCount = cur.ackedKeyCount + acked,
                                    lastRttMs = fwd.rttMs,
                                    channelKind = ChannelKind.TAILSCALE,
                                    activeChannelLabel = formatTailscaleChannelLabel(updatedBridge),
                                    consecutiveTailscaleFailures = 0,
                                )
                            }
                            errorMessageState.value = null
                            Ticker.refresh(type)
                            return
                        }
                        updateActiveSession(sessionId) { cur ->
                            cur.copy(channelKind = ChannelKind.PROBING, activeChannelLabel = "TS 发送失败 (${fwd.detail})")
                        }
                        errorMessageState.value = "Tailscale 发送失败: ${outcome.bridge.targetEndpoint.host}:${outcome.bridge.targetEndpoint.port} (${fwd.detail})"
                        Ticker.refresh(type)
                        return
                    }
                    is TailscaleBridgeProbeOutcome.BridgeUnreachable -> {
                        var directTsEp: DirectTargetEndpoint? = null
                        var directRtt = 0
                        for (ep in tsCandidates) {
                            val t0 = System.nanoTime()
                            val res = RemoteKeyboardClient.sendDirectTcpWithStatus(ep, payloadBytes)
                            if (res.ok) {
                                directTsEp = ep
                                directRtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                                break
                            }
                        }
                        if (directTsEp != null) {
                            updateActiveSession(sessionId) { cur ->
                                cur.copy(
                                    verifiedTailscaleBridge = null,
                                    verifiedDirectEndpoint = directTsEp,
                                    ackedKeyCount = cur.ackedKeyCount + batch.size,
                                    lastRttMs = directRtt,
                                    channelKind = ChannelKind.TAILSCALE,
                                    activeChannelLabel = formatDirectChannelLabel(directTsEp, directRtt),
                                    consecutiveDirectFailures = 0,
                                )
                            }
                            errorMessageState.value = null
                            Ticker.refresh(type)
                            return
                        }
                        val firstEp = tsCandidates.first()
                        updateActiveSession(sessionId) { cur ->
                            cur.copy(channelKind = ChannelKind.PROBING, activeChannelLabel = "TS 桥接未连接")
                        }
                        errorMessageState.value = "Tailscale 桥接未连接 (VM :${VmKeyboardBridgeClient.BRIDGE_PORT} 未响应) 且直连 ${firstEp.host}:${firstEp.port} 失败"
                        Ticker.refresh(type)
                        return
                    }
                    is TailscaleBridgeProbeOutcome.TargetUnreachable -> {
                        updateActiveSession(sessionId) { cur ->
                            cur.copy(
                                channelKind = ChannelKind.PROBING,
                                activeChannelLabel = "TS 未通 (${outcome.detail})",
                            )
                        }
                        errorMessageState.value = "强制 Tailscale 未通: ${outcome.endpoint.host}:${outcome.endpoint.port} (${outcome.detail})"
                        Ticker.refresh(type)
                        return
                    }
                }
            }
            return
        }

        // 1. Direct P2P / LAN path (prefer reliable TCP keep-alive; require real ACK in direct mode)
        if (currentSession.transportMode == RemoteKeyboardProtocol.MODE_DIRECT) {
            val candidates = directCandidatesFor(currentSession)
            if (candidates.isEmpty()) {
                errorMessageState.value = "未找到目标设备的局域网 IP，请填写直连地址或切换为自动模式。"
                return
            }
            val tcpTargets = if (currentSession.verifiedDirectEndpoint != null) {
                candidates.take(4)
            } else {
                candidates.take(MAX_DIRECT_CANDIDATES)
            }
            var deliveredEp: DirectTargetEndpoint? = null
            var deliveredRttMs = 0
            val failures = ArrayList<Pair<DirectTargetEndpoint, String>>()
            for (ep in tcpTargets) {
                val t0 = System.nanoTime()
                val res = RemoteKeyboardClient.sendDirectTcpWithStatus(ep, payloadBytes)
                if (res.ok) {
                    deliveredEp = ep
                    deliveredRttMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                    break
                }
                val reason = res.detail.takeIf { it.isNotBlank() } ?: "目标未响应"
                failures.add(ep to reason)
            }
            if (deliveredEp == null && !isStoppingReleaseAll) {
                val udpEp = candidates.first()
                val t0 = System.nanoTime()
                if (RemoteKeyboardClient.sendDirectUdpWithAck(udpEp, payloadBytes)) {
                    deliveredEp = udpEp
                    deliveredRttMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            }
            if (deliveredEp != null) {
                updateActiveSession(sessionId) { cur ->
                    val kind = if (RemoteKeyboardProtocol.isTailscaleIpv4(deliveredEp.host)) {
                        ChannelKind.TAILSCALE
                    } else {
                        ChannelKind.DIRECT
                    }
                    val switched = cur.channelKind != kind || cur.verifiedDirectEndpoint != deliveredEp
                    cur.copy(
                        verifiedDirectEndpoint = deliveredEp,
                        ackedKeyCount = cur.ackedKeyCount + batch.size,
                        lastRttMs = deliveredRttMs,
                        channelKind = kind,
                        activeChannelLabel = formatDirectChannelLabel(deliveredEp, deliveredRttMs),
                        consecutiveDirectFailures = 0,
                    ).also { if (switched) Ticker.refresh(type) }
                }
                errorMessageState.value = null
            } else {
                val summary = if (failures.isNotEmpty()) {
                    failures.take(2).joinToString("；") { (ep, reason) -> "${ep.host}:${ep.port} ($reason)" }
                } else {
                    val firstEp = candidates.first()
                    "${firstEp.host}:${firstEp.port} (目标未响应)"
                }
                val firstFailure = failures.firstOrNull()?.second ?: "目标未响应"
                updateActiveSession(sessionId) { cur ->
                    val switched = cur.channelKind != ChannelKind.PROBING || cur.verifiedDirectEndpoint != null
                    cur.copy(
                        verifiedDirectEndpoint = null,
                        channelKind = ChannelKind.PROBING,
                        activeChannelLabel = "直连未通 ($firstFailure)",
                    ).also { if (switched) Ticker.refresh(type) }
                }
                errorMessageState.value = "局域网直连失败: $summary"
                Ticker.refresh(type)
            }
            return
        }

        // In AUTO mode, route Tailscale bridge first, then LAN direct TCP (zero duplicate VPS relay race).
        if (currentSession.transportMode == RemoteKeyboardProtocol.MODE_AUTO) {
            val verifiedTs = currentSession.verifiedTailscaleBridge
            if (verifiedTs != null) {
                val fwd = VmKeyboardBridgeClient.sendForwardOverBridge(verifiedTs, payloadJson)
                if (fwd.ok) {
                    val updatedBridge = verifiedTs.copy(rttMs = fwd.rttMs)
                    val acked = fwd.accepted.takeIf { it > 0 } ?: batch.size
                    updateActiveSession(sessionId) { cur ->
                        val switched = cur.channelKind != ChannelKind.TAILSCALE
                        cur.copy(
                            verifiedTailscaleBridge = updatedBridge,
                            ackedKeyCount = cur.ackedKeyCount + acked,
                            lastRttMs = fwd.rttMs,
                            channelKind = ChannelKind.TAILSCALE,
                            activeChannelLabel = formatTailscaleChannelLabel(updatedBridge),
                            consecutiveTailscaleFailures = 0,
                        ).also { if (switched) Ticker.refresh(type) }
                    }
                    errorMessageState.value = null
                    return
                }
                val newTsFailures = currentSession.consecutiveTailscaleFailures + 1
                if (newTsFailures >= 2) {
                    updateActiveSession(sessionId) { cur ->
                        cur.copy(
                            verifiedTailscaleBridge = null,
                            consecutiveTailscaleFailures = 0,
                        )
                    }
                    Ticker.refresh(type)
                } else {
                    updateActiveSession(sessionId) { cur ->
                        cur.copy(consecutiveTailscaleFailures = newTsFailures)
                    }
                }
            }

            val verified = currentSession.verifiedDirectEndpoint
            if (verified != null) {
                val t0 = System.nanoTime()
                val res = RemoteKeyboardClient.sendDirectTcpWithStatus(verified, payloadBytes)
                if (res.ok) {
                    val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                    val kind = if (RemoteKeyboardProtocol.isTailscaleIpv4(verified.host)) {
                        ChannelKind.TAILSCALE
                    } else {
                        ChannelKind.DIRECT
                    }
                    updateActiveSession(sessionId) { cur ->
                        val switched = cur.channelKind != kind
                        cur.copy(
                            ackedKeyCount = cur.ackedKeyCount + batch.size,
                            lastRttMs = rtt,
                            channelKind = kind,
                            activeChannelLabel = formatDirectChannelLabel(verified, rtt),
                            consecutiveDirectFailures = 0,
                        ).also { if (switched) Ticker.refresh(type) }
                    }
                    errorMessageState.value = null
                    return
                }
                val newFailures = currentSession.consecutiveDirectFailures + 1
                if (newFailures >= 2) {
                    updateActiveSession(sessionId) { cur ->
                        cur.copy(
                            verifiedDirectEndpoint = null,
                            channelKind = ChannelKind.RELAY,
                            activeChannelLabel = "VPS 中转",
                            consecutiveDirectFailures = 0,
                        )
                    }
                    Ticker.refresh(type)
                } else {
                    updateActiveSession(sessionId) { cur ->
                        cur.copy(consecutiveDirectFailures = newFailures)
                    }
                }
            }

            if (currentSession.verifiedTailscaleBridge == null && currentSession.verifiedDirectEndpoint == null) {
                val nowMs = System.currentTimeMillis()
                val lastProbe = maxOf(currentSession.lastTailscaleProbeMs, currentSession.lastDirectProbeMs)
                if (!isStoppingReleaseAll && nowMs - lastProbe >= REPROBE_INTERVAL_MS) {
                    updateActiveSession(sessionId) { cur ->
                        cur.copy(lastTailscaleProbeMs = nowMs, lastDirectProbeMs = nowMs)
                    }
                    probeDirectEndpointsAsync(currentSession)
                }
            }
        }

        // 2. VPS Relay path (works across any NAT / network; reuses TLS Keep-Alive + retries on hiccups)
        val relayT0 = System.nanoTime()
        runCatching {
            val reply = sendRelayWithRetry(id, req, sessionId, allowAbortCheck = !isStoppingReleaseAll)
            val relayRtt = ((System.nanoTime() - relayT0) / 1_000_000L).toInt().coerceAtLeast(1)
            updateActiveSession(sessionId) { cur ->
                val targetKind = when {
                    cur.verifiedTailscaleBridge != null -> ChannelKind.TAILSCALE
                    cur.verifiedDirectEndpoint != null -> cur.channelKind
                    else -> ChannelKind.RELAY
                }
                val switched = cur.channelKind != targetKind
                val hasDirect = cur.verifiedTailscaleBridge != null || cur.verifiedDirectEndpoint != null
                val label = if (hasDirect) {
                    cur.activeChannelLabel
                } else if (reply.targetReceiverReady) {
                    "VPS 中转 (对端已连接 · ${relayRtt}ms)"
                } else {
                    "VPS 中转 (等待对端 · ${relayRtt}ms)"
                }
                cur.copy(
                    channelKind = targetKind,
                    activeChannelLabel = label,
                    lastRttMs = if (hasDirect) cur.lastRttMs else relayRtt,
                    ackedKeyCount = cur.ackedKeyCount + (reply.accepted.takeIf { it > 0 } ?: batch.size),
                    targetReceiverReady = reply.targetReceiverReady,
                ).also { if (switched) Ticker.refresh(type) }
            }
            errorMessageState.value = null
        }.onFailure { e ->
            errorMessageState.value = e.message ?: "发送按键失败"
            Ticker.refresh(type)
        }
    }

    fun sendTextAsKeystrokes(text: String) {
        val session = activeSessionState.value ?: return
        val cleaned = text.replace("\r\n", "\n")
        if (cleaned.isEmpty()) return
        val ev = RemoteKeyboardEvent(
            seq = eventSeq.getAndIncrement(),
            action = "text",
            text = cleaned,
            keyName = "Text(${cleaned.length})",
            sentAtMs = System.currentTimeMillis(),
        )
        enqueueEvent(ev, session)
        statusMessageState.value = "已发送文本 (${cleaned.length} 字符) 到 ${session.target.displayTitle}"
    }

    fun refreshDevices(quiet: Boolean = false) {
        ensureInitialized()
        refreshIdentityFromStore()
        val id = identityState.value
        loadingDevicesState.value = true
        if (!quiet) errorMessageState.value = null
        deviceExecutor.execute {
            val useDirectVps = id.isRegistered || id.sessionCookie.isNotBlank()
            val listRes: Result<List<RemoteDeviceSummary>> = if (useDirectVps) {
                runCatching { RemoteKeyboardClient.fetchDevices(id) }
            } else {
                runCatching {
                    val bridgeRes = VmKeyboardBridgeClient.fetchDevicesViaBridge(executor = deviceExecutor)
                    bridgeIdentityState.value = VmKeyboardBridgeHealthResponse(
                        ok = true,
                        port = VmKeyboardBridgeClient.BRIDGE_PORT,
                        identityReady = true,
                        selfDeviceId = bridgeRes.selfDeviceId,
                        selfDisplayName = bridgeRes.selfDisplayName,
                    )
                    bridgeRes.devices
                }.onFailure {
                    bridgeIdentityState.value = null
                }
            }
            loadingDevicesState.value = false
            listRes.onSuccess { list ->
                devicesState.value = list
                // Update active session's target summary if active
                activeSessionState.value?.let { cur ->
                    val updatedTarget = list.firstOrNull { it.id == cur.target.id }
                    if (updatedTarget != null) {
                        activeSessionState.value = cur.copy(
                            target = updatedTarget,
                            targetReceiverReady = cur.targetReceiverReady || updatedTarget.keyboardActive,
                        )
                    }
                }
                if (!quiet) {
                    val viaHint = if (useDirectVps) "" else "（经由 VM 网桥）"
                    statusMessageState.value = "已同步 ${list.size} 台设备$viaHint"
                }
                errorMessageState.value = null
            }.onFailure { e ->
                if (!quiet) {
                    errorMessageState.value = e.message ?: "拉取设备列表失败"
                }
            }
            Ticker.refresh(type)
        }
    }

    fun copyToClipboard(text: String) {
        runCatching {
            val cm = Env.app.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("text", text))
        }
    }

    private fun toastOnMain(message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(Env.app, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun iconNameForSession(session: ActiveSession?): String = when (session?.channelKind) {
        ChannelKind.TAILSCALE, ChannelKind.DIRECT -> "brand:remote_keyboard_direct"
        ChannelKind.RELAY -> "brand:remote_keyboard_relay"
        else -> "brand:remote_keyboard"
    }

    override fun state(item: ItemConfig): ItemState {
        ensureInitialized()
        val session = activeSessionState.value
        val devices = devicesState.value
        val error = errorMessageState.value
        val exitShortcut = exitShortcutOf(item)
        val showTextInBar = item.optBool("showActiveText", true)
        val iconName = iconNameForSession(session)

        val barText = if (session != null && showTextInBar) {
            val badge = shortChannelBadge(session)
            "${session.target.displayTitle.take(7)} · $badge"
        } else {
            null
        }

        val channelIp = session?.verifiedTailscaleBridge?.targetEndpoint?.host
            ?: session?.verifiedDirectEndpoint?.host
            ?: ""

        val statusSummary = when {
            session != null -> buildString {
                append(session.target.displayTitle)
                append(" [")
                append(shortChannelBadge(session))
                append("]")
                if (channelIp.isNotEmpty()) {
                    append(" ")
                    append(channelIp)
                }
                append(" · 已发 ")
                append(session.sentKeyCount)
                append("/")
                append(session.ackedKeyCount)
                append(" 键 · 按 ")
                append(exitShortcut.format())
                append("退出")
            }
            error != null -> error
            devices.any { it.isKeyboardMenuVisible } -> {
                val onlineCount = devices.count { it.isKeyboardMenuVisible }
                "就绪 · $onlineCount 台可映射电脑在线"
            }
            devices.isNotEmpty() -> "暂无在线的可映射电脑"
            identityState.value.isRegistered -> "就绪 · 点击选择目标电脑开启映射"
            bridgeIdentityState.value?.identityReady == true ->
                "已通过 VM 网桥接入 (${bridgeIdentityState.value?.selfDisplayName?.ifBlank { "googlebook" }})"
            else -> "未注册 VPS 设备"
        }

        return ItemState(
            icon = iconName,
            filled = session != null,
            text = barText,
            label = session?.target?.displayTitle ?: stringResourceSafe(R.string.item_remote_keyboard_title, "远程键盘映射"),
            active = session != null,
            tone = when {
                session != null -> Tone.ACCENT
                error != null -> Tone.WARN
                else -> Tone.NORMAL
            },
            desc = "远程键盘映射: $statusSummary",
            tooltip = "远程键盘映射 · $statusSummary",
            textLimit = 22,
            widthKey = if (session != null) "rk_active" else null,
        )
    }

    private fun stringResourceSafe(resId: Int, fallback: String): String =
        runCatching { Env.app.getString(resId) }.getOrDefault(fallback)

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        ensureInitialized()
        val id by identityState.collectAsState()
        val bridgeInfo by bridgeIdentityState.collectAsState()
        val devices by devicesState.collectAsState()
        val session by activeSessionState.collectAsState()
        val loading by loadingDevicesState.collectAsState()
        val statusMsg by statusMessageState.collectAsState()
        val errorMsg by errorMessageState.collectAsState()

        val exitShortcut = remember(item) { exitShortcutOf(item) }
        val transportMode = remember(item) { transportModeOf(item) }
        val selectedTarget = remember(item, devices) { selectedTargetFor(item, devices) }
        var quickTypeText by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            refreshIdentityFromStore()
            refreshDevices(quiet = true)
        }

        val bridgeReady = bridgeInfo?.identityReady == true
        val subtitle = when {
            session != null -> "映射中 → ${session!!.target.displayTitle} · ${session!!.activeChannelLabel}"
            loading -> "正在同步设备列表…"
            id.isRegistered -> "已连接 (${id.displayName.ifBlank { "本机" }}) · 退出键 ${exitShortcut.format()}"
            bridgeReady -> "已通过 VM 网桥接入 (${bridgeInfo?.selfDisplayName?.ifBlank { "googlebook" }}) · 退出键 ${exitShortcut.format()}"
            else -> "未注册设备 · 请先在设置或云剪切板中绑定 VPS"
        }

        MenuCard(
            icon = iconNameForSession(session),
            title = stringResource(R.string.item_remote_keyboard_title),
            subtitle = subtitle,
            subtitleColor = when {
                errorMsg != null -> MaterialTheme.colorScheme.error
                session != null -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            trailing = {
                SmallIconButton(
                    sym = Sym.REFRESH,
                    label = "刷新设备列表",
                    enabled = !loading,
                ) {
                    refreshDevices(quiet = false)
                }
            },
        ) {
            // 1. Active mapping banner & one-click stop
            if (session != null) {
                val active = session!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "本机键盘已接管 → ${active.target.displayTitle}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(shortChannelBadge(active))
                                    append(" · ")
                                    append(active.activeChannelLabel)
                                    append(" · 已发 ")
                                    append(active.sentKeyCount)
                                    append(" / 已达 ")
                                    append(active.ackedKeyCount)
                                    append(" 键")
                                    if (active.lastKeyLabel.isNotBlank()) {
                                        append(" (")
                                        append(active.lastKeyLabel)
                                        append(")")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "退出方式：按 ${exitShortcut.format()} / 连按 3 次 Esc / 点击状态栏图标",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { stopMapping(reason = "已手动关闭远程键盘映射") },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("停止映射", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (!active.targetReceiverReady && !active.target.keyboardActive) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (active.target.isMac) {
                                "提示：请确认 Mac 电脑已运行最新版 nettransfer-agentd 并开启了辅助功能（Accessibility）权限。"
                            } else {
                                "提示：请确认目标 Windows 电脑已运行最新版 SafetyGuard（并在「云剪切板 › 远程键盘映射接收」中保持开启）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                // Quick text injection row while active (useful for pasting passwords / long strings)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = quickTypeText,
                        onValueChange = { quickTypeText = it },
                        label = { Text("发送整段文字/密码到对端电脑") },
                        placeholder = { Text("支持直接把本机剪贴板文字键入对端") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        enabled = quickTypeText.isNotEmpty(),
                        onClick = {
                            val toSend = quickTypeText
                            quickTypeText = ""
                            sendTextAsKeystrokes(toSend)
                        },
                    ) {
                        Text("键入")
                    }
                }
                MenuEntry(
                    icon = Sym.CONTENT_PASTE,
                    label = "将本机当前剪贴板文字直接键入对端电脑",
                    sub = "适合在对端锁屏或无剪切板同步时输入文本",
                ) {
                    val clip = CloudClipboardItem.readLocalClipboard()
                    if (!clip.isNullOrEmpty()) {
                        sendTextAsKeystrokes(clip)
                    } else {
                        toastOnMain("本机剪贴板没有可键入的文字")
                    }
                }
                MenuDivider()
            } else if (selectedTarget != null) {
                // Quick start button for the currently selected target PC
                MenuEntry(
                    icon = Sym.BOLT,
                    label = "开启键盘映射 → ${selectedTarget.displayTitle}",
                    sub = "${selectedTarget.platformLabel} · 开启后本机按键将全部发往该电脑（按 ${exitShortcut.format()} 退出）",
                ) {
                    host.close()
                    startMapping(selectedTarget, item)
                }
                MenuDivider()
            }

            // 2. Transport mode selector
            SectionLabel("传输通道模式")
            val modeLabels = listOf("自动 (TS 优先)", "强制 Tailscale", "局域网直连", "VPS 中转")
            val modeValues = listOf(
                RemoteKeyboardProtocol.MODE_AUTO,
                RemoteKeyboardProtocol.MODE_TAILSCALE,
                RemoteKeyboardProtocol.MODE_DIRECT,
                RemoteKeyboardProtocol.MODE_RELAY,
            )
            val currentModeIdx = modeValues.indexOf(transportMode).coerceAtLeast(0)
            ChipRow(
                labels = modeLabels,
                selected = currentModeIdx,
            ) { idx ->
                val chosen = modeValues[idx]
                Store.updateItem(item.id) {
                    it.with("transportMode", chosen.takeIf { m -> m != RemoteKeyboardProtocol.MODE_AUTO })
                }
            }

            if (errorMsg != null) {
                MenuNote(errorMsg!!)
            } else if (statusMsg != null) {
                MenuNote(statusMsg!!)
            }

            // 3. Target device list from vps.why.band
            MenuDivider()
            val desktopDevices = menuTargetsFor(devices, session?.target?.id)
            SectionLabel("目标电脑列表 (${desktopDevices.size})")

            if (desktopDevices.isNotEmpty()) {
                desktopDevices.forEach { dev ->
                    val assignedShortcut = deviceShortcutOf(item, dev.id)
                    val isSelected = selectedTarget?.id == dev.id
                    val isCurrentlyMapped = session?.target?.id == dev.id
                    val tsIp = dev.tailscaleIpCandidates().firstOrNull()
                    val lanIp = dev.lanIpv4Addresses.firstOrNull { !RemoteKeyboardProtocol.isTailscaleIpv4(it) }
                    val statusTag = buildString {
                        append(dev.platformLabel)
                        if (assignedShortcut != null) {
                            append(" · 快捷键 ${assignedShortcut.format()}")
                        }
                        append(if (dev.online) " · 在线" else " · 离线")
                        if (dev.keyboardActive) append(" · 接收就绪")
                        if (!tsIp.isNullOrBlank()) append(" · TS $tsIp")
                        if (!lanIp.isNullOrBlank()) append(" · LAN $lanIp")
                        else if (dev.sourceIp.isNotBlank() && tsIp.isNullOrBlank()) append(" · ${dev.sourceIp}")
                    }
                    MenuEntry(
                        icon = if (isCurrentlyMapped) Sym.BOLT else if (isSelected) Sym.CHECK else Sym.DESKTOP_WINDOWS,
                        label = dev.displayTitle,
                        sub = statusTag,
                        detail = if (isCurrentlyMapped) "映射中" else if (isSelected) "已选" else "点击开启",
                    ) {
                        Store.updateItem(item.id) { it.with("targetDeviceId", dev.id) }
                        if (isCurrentlyMapped) {
                            stopMapping(reason = "已停止对 ${dev.displayTitle} 的键盘映射")
                        } else {
                            host.close()
                            startMapping(dev, item.with("targetDeviceId", dev.id))
                        }
                    }
                }
            } else {
                MenuNote(
                    if (id.isRegistered || bridgeReady) {
                        "当前没有在线且支持键盘映射的桌面设备（仅显示在线的 Windows / macOS 设备）。"
                    } else {
                        "尚未连接云服务器。请打开下方设置填写面板地址与注册码（与云剪切板共用同一设备凭证），或确认 Debian VM 守护服务已运行。"
                    }
                )
            }

            MenuDivider()
            if (session != null) {
                MenuEntry(
                    icon = Sym.BOLT,
                    label = "一键发送测试按键 (\"ok\") 到 ${session!!.target.displayTitle}",
                    sub = "用于排查链路：在对端打开记事本/文本编辑，点击此项应立即键入 ok",
                ) {
                    sendTextAsKeystrokes("ok")
                }
            }
            MenuEntry(
                icon = Sym.CONTENT_PASTE,
                label = "复制 macOS 接收端一键更新命令",
                sub = "curl -fsSL https://vps.why.band/download/update-keyboard-macos.sh | bash",
            ) {
                copyToClipboard("curl -fsSL https://vps.why.band/download/update-keyboard-macos.sh | bash")
                toastOnMain("已复制 macOS 一键更新命令，在 Mac 终端粘贴回车即可")
            }
            MenuEntry(
                icon = Sym.OPEN_IN_NEW,
                label = "打开 VPS 设备管理面板",
            ) {
                host.close()
                val targetUrl = id.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }
                Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            }
            MenuEntry(
                icon = Sym.SETTINGS,
                label = stringResource(R.string.context_item_settings),
            ) {
                host.openItemSettings(item.id)
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ensureInitialized()
        val id by identityState.collectAsState()
        val bridgeInfo by bridgeIdentityState.collectAsState()
        val devices by devicesState.collectAsState()
        val loading by loadingDevicesState.collectAsState()
        val statusMsg by statusMessageState.collectAsState()
        val errorMsg by errorMessageState.collectAsState()

        val currentExitShortcut = exitShortcutOf(item)
        var recordingShortcut by remember { mutableStateOf(false) }
        val captureFocus = remember { FocusRequester() }

        var recordingDeviceId by remember { mutableStateOf<String?>(null) }
        val deviceFocusRequester = remember { FocusRequester() }

        LaunchedEffect(recordingShortcut) {
            if (recordingShortcut) {
                runCatching { captureFocus.requestFocus() }
            }
        }

        LaunchedEffect(recordingDeviceId) {
            if (recordingDeviceId != null) {
                runCatching { deviceFocusRequester.requestFocus() }
            }
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("退出键盘映射组合键 (安全锁恢复)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (recordingShortcut) 2.dp else 1.dp,
                        color = if (recordingShortcut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .focusRequester(captureFocus)
                    .onFocusChanged { if (!it.isFocused) recordingShortcut = false }
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!recordingShortcut) return@onPreviewKeyEvent false
                        val native = keyEvent.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            if (native.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                                !native.isCtrlPressed && !native.isAltPressed && !native.isShiftPressed
                            ) {
                                recordingShortcut = false
                                return@onPreviewKeyEvent true
                            }
                            val captured = CloudClipboardShortcut.fromKeyEvent(native)
                            if (captured != null) {
                                set(item.with("exitShortcut", captured.serialize()))
                                recordingShortcut = false
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .clickable { recordingShortcut = !recordingShortcut }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (recordingShortcut) "请按下退出组合键 (如 Ctrl + Alt + Q)…" else "当前退出组合键：${currentExitShortcut.format()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (recordingShortcut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "键盘映射开启期间，本机所有按键均发往对端电脑，仅此组合键可在本地拦截并退出映射",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { recordingShortcut = !recordingShortcut }) {
                    Text(if (recordingShortcut) "取消录制" else "录制退出键")
                }
            }

            val presetLabels = RemoteKeyboardProtocol.EXIT_SHORTCUT_PRESETS.map { it.format() }
            val selectedPresetIndex = RemoteKeyboardProtocol.EXIT_SHORTCUT_PRESETS.indexOfFirst { it == currentExitShortcut }.takeIf { it >= 0 }
            ChipRow(
                labels = presetLabels,
                selected = selectedPresetIndex,
            ) { idx ->
                val chosen = RemoteKeyboardProtocol.EXIT_SHORTCUT_PRESETS[idx]
                set(item.with("exitShortcut", chosen.serialize().takeIf { it != RemoteKeyboardProtocol.DEFAULT_EXIT_SHORTCUT.serialize() }))
            }

            SwitchRow(
                label = "启用紧急连按 3 次 Esc 退出映射",
                checked = item.optBool("tripleEscExit", true),
                help = "快速连续按 3 次 Esc 键时自动结束远程键盘映射并恢复本机输入",
            ) { enabled ->
                set(item.with("tripleEscExit", if (enabled) null else "false"))
            }

            SwitchRow(
                label = "映射开启时左键点击状态栏图标直接关闭映射",
                checked = item.optBool("clickToStop", true),
                help = "用鼠标直接点击状态栏上的远程键盘图标即可一键停止映射",
            ) { enabled ->
                set(item.with("clickToStop", if (enabled) null else "false"))
            }

            SwitchRow(
                label = "未映射时左键点击状态栏图标直接开启映射",
                checked = item.optBool("clickToStart", false),
                help = "开启后左键点击直接开始控制默认目标电脑（右键打开菜单切换设备）",
            ) { enabled ->
                set(item.with("clickToStart", if (enabled) "true" else null))
            }

            SwitchRow(
                label = "映射开启时在状态栏显示目标电脑名称",
                checked = item.optBool("showActiveText", true),
                help = "在图标旁显示当前受控电脑名称，清晰提示本机键盘正处于远程映射状态",
            ) { enabled ->
                set(item.with("showActiveText", if (enabled) null else "false"))
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("各设备快捷启用键 (免开菜单直接接管控制)", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "为每台目标电脑绑定专属快捷键（如 Alt + 1、Alt + 2、Ctrl + Alt + M），在任何界面按下即可瞬间激活键盘接管",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            val desktopList = shortcutEligibleTargetsFor(item, devices)
            if (desktopList.isNotEmpty()) {
                desktopList.forEach { dev ->
                    val isRecordingThis = recordingDeviceId == dev.id
                    val currentShortcut = deviceShortcutOf(item, dev.id)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isRecordingThis) 2.dp else 1.dp,
                                color = if (isRecordingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .then(
                                if (isRecordingThis) {
                                    Modifier
                                        .focusRequester(deviceFocusRequester)
                                        .onFocusChanged { if (!it.isFocused) recordingDeviceId = null }
                                        .focusable()
                                        .onPreviewKeyEvent { keyEvent ->
                                            val native = keyEvent.nativeKeyEvent
                                            if (native.action == KeyEvent.ACTION_DOWN) {
                                                if (native.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                                                    !native.isCtrlPressed && !native.isAltPressed && !native.isShiftPressed
                                                ) {
                                                    recordingDeviceId = null
                                                    return@onPreviewKeyEvent true
                                                }
                                                val captured = CloudClipboardShortcut.fromKeyEvent(native)
                                                if (captured != null) {
                                                    set(item.with(deviceShortcutOptionKey(dev.id), captured.serialize()))
                                                    recordingDeviceId = null
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                            false
                                        }
                                } else Modifier
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${dev.displayTitle} (${dev.platformLabel})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (isRecordingThis) {
                                        "请按下专属快捷键 (例如 Alt + 1 或 Ctrl + Alt + 1)…"
                                    } else if (currentShortcut != null) {
                                        "当前快捷启用键：${currentShortcut.format()}"
                                    } else {
                                        "未配置快捷键 (可点击下方预设或录制)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isRecordingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { recordingDeviceId = if (isRecordingThis) null else dev.id }) {
                                Text(if (isRecordingThis) "取消" else "录制按键")
                            }
                        }

                        // Preset chips row
                        val presetLabels = RemoteKeyboardProtocol.DEVICE_ACTIVATION_SHORTCUT_PRESETS.map { it.format() } + listOf("清除快捷键")
                        val selectedIdx = RemoteKeyboardProtocol.DEVICE_ACTIVATION_SHORTCUT_PRESETS.indexOfFirst { it == currentShortcut }.takeIf { it >= 0 }
                        ChipRow(
                            labels = presetLabels,
                            selected = selectedIdx,
                        ) { idx ->
                            if (idx < RemoteKeyboardProtocol.DEVICE_ACTIVATION_SHORTCUT_PRESETS.size) {
                                val chosen = RemoteKeyboardProtocol.DEVICE_ACTIVATION_SHORTCUT_PRESETS[idx]
                                set(item.with(deviceShortcutOptionKey(dev.id), chosen.serialize()))
                            } else {
                                set(item.with(deviceShortcutOptionKey(dev.id), null))
                            }
                        }
                    }
                }
            } else {
                MenuNote("尚未获取到目标桌面电脑列表，请在下方刷新或配置 VPS 后再行设定快捷键。")
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("直连与打洞覆盖配置 (可选)", style = MaterialTheme.typography.labelLarge)
            var directAddressInput by remember(item.options["directAddress"]) {
                mutableStateOf(item.opt("directAddress", ""))
            }
            TextRow(
                label = "自定义对端直连地址 / SSH 隧道端口 (IP:Port)",
                value = directAddressInput,
                placeholder = "例如 192.168.1.50:19878 或 127.0.0.1:19878",
                help = "留空则自动根据 VPS 下发的设备局域网 IP 与来源 IP 探测 19878 端口；若走本地 SSH 隧道可填转发地址",
            ) { next ->
                directAddressInput = next
                set(item.with("directAddress", next.trim().takeIf { it.isNotEmpty() }))
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("云服务器与设备列表状态 (共享 NetTransfer 凭证)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val bridgeReady = bridgeInfo?.identityReady == true
            InfoRow(
                label = "VPS 凭证状态",
                value = when {
                    id.isRegistered -> "已注册 (${id.displayName.ifBlank { "本机" }})"
                    bridgeReady -> "已通过 VM 网桥接入 (${bridgeInfo?.selfDisplayName?.ifBlank { "googlebook" }})"
                    else -> "未注册设备"
                },
                valueColor = if (id.isRegistered || bridgeReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(
                label = "可映射桌面设备",
                value = "${devices.count { it.isKeyboardMenuVisible }} 台在线（已隐藏离线及 Linux 服务器）",
            )
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else if (statusMsg != null) {
                Text(
                    text = statusMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    enabled = !loading,
                    onClick = { refreshDevices(quiet = false) },
                ) {
                    Text("刷新设备列表")
                }
            }
        }
    }

    override fun debug(args: List<String>): String? {
        ensureInitialized()
        return when (args.firstOrNull()) {
            null -> "registered=${identityState.value.isRegistered} devices=${devicesState.value.size} active=${activeSessionState.value != null}"
            "sync" -> {
                refreshDevices(quiet = false)
                "syncing remote devices"
            }
            "stop" -> {
                stopMapping(reason = "stopped via debug")
                "stopped remote keyboard mapping"
            }
            "stage" -> {
                val sample = listOf(
                    RemoteDeviceSummary(
                        id = "dev-win-1",
                        name = "Workstation-Win11",
                        hostname = "WORKSTATION",
                        platform = "windows",
                        os = "windows",
                        online = true,
                        keyboardActive = true,
                    ),
                    RemoteDeviceSummary(
                        id = "dev-mac-1",
                        name = "Mac-mini",
                        hostname = "Mac-mini.local",
                        platform = "darwin",
                        os = "darwin",
                        online = true,
                        keyboardActive = true,
                    ),
                )
                devicesState.value = sample
                Ticker.refresh(type)
                "staged 2 remote computers"
            }
            else -> null
        }
    }
}

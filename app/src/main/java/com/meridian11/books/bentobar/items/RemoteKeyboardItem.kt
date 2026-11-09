package com.meridian11.books.bentobar.items

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
    override val menuWidthDp = 370
    override val canBeActive = true
    override val discreet = true
    override val trigger = Trigger(R.string.trigger_remote_keyboard, R.string.trigger_remote_keyboard_short)

    data class ActiveSession(
        val target: RemoteDeviceSummary,
        val transportMode: String, // "auto" | "relay" | "direct"
        val customDirectAddress: String = "",
        val startedAtMs: Long = System.currentTimeMillis(),
        val activeChannelLabel: String = "连接中…",
        val verifiedDirectEndpoint: DirectTargetEndpoint? = null,
        val sentKeyCount: Long = 0L,
        val lastKeyLabel: String = "",
        val targetReceiverReady: Boolean = true,
    )

    private val deviceExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    internal val identityState = MutableStateFlow(NetTransferIdentity())
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
        val id = identityState.value
        if (id.isRegistered || id.sessionCookie.isNotBlank()) {
            refreshDevices(quiet = true)
        }
    }

    override fun onIdle() {
        if (activeSessionState.value != null) {
            stopMapping(reason = "状态栏已隐藏，自动结束远程键盘映射")
        }
    }

    override fun sample(now: Long) {
        ensureInitialized()
        refreshIdentityFromStore()
        val id = identityState.value
        val interval = if (activeSessionState.value != null) 15_000L else 45_000L
        if ((id.isRegistered || id.sessionCookie.isNotBlank()) && now - lastDeviceSyncMs >= interval) {
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

    internal fun isTripleEscEnabled(item: ItemConfig?): Boolean =
        item?.optBool("tripleEscExit", true) ?: true

    internal fun transportModeOf(item: ItemConfig?): String {
        val raw = item?.opt("transportMode", RemoteKeyboardProtocol.MODE_AUTO)?.lowercase() ?: RemoteKeyboardProtocol.MODE_AUTO
        return when (raw) {
            RemoteKeyboardProtocol.MODE_RELAY, RemoteKeyboardProtocol.MODE_DIRECT -> raw
            else -> RemoteKeyboardProtocol.MODE_AUTO
        }
    }

    internal fun customDirectAddressOf(item: ItemConfig?): String =
        item?.opt("directAddress", "")?.trim().orEmpty()

    internal fun selectedTargetIdOf(item: ItemConfig?): String =
        item?.opt("targetDeviceId", "")?.trim().orEmpty()

    internal fun selectedTargetFor(item: ItemConfig?, devices: List<RemoteDeviceSummary>): RemoteDeviceSummary? {
        val wantedId = selectedTargetIdOf(item)
        if (wantedId.isNotEmpty()) {
            devices.firstOrNull { it.id == wantedId }?.let { return it }
        }
        return devices.firstOrNull { it.isDesktopTarget && it.online }
            ?: devices.firstOrNull { it.isDesktopTarget }
            ?: devices.firstOrNull()
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
     * - Returns `true` for every key event while mapping is active so the local Googlebook ignores
     *   all keyboard input and forwards it to the remote computer.
     * - Checks for the exit shortcut (default `Ctrl + Alt + Q`) or rapid `Esc x 3` to exit mapping
     *   and release any held keys on the remote computer.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val session = activeSessionState.value ?: return false
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
        if (mode != RemoteKeyboardProtocol.MODE_DIRECT && !id.isRegistered && id.sessionCookie.isBlank()) {
            errorMessageState.value = "请先在设置或云剪切板中完成 VPS 设备注册。"
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

        val initialChannel = when (mode) {
            RemoteKeyboardProtocol.MODE_DIRECT -> "局域网直连探测中…"
            RemoteKeyboardProtocol.MODE_RELAY -> "VPS 中转"
            else -> "VPS 中转 + 直连探测"
        }

        val session = ActiveSession(
            target = target,
            transportMode = mode,
            customDirectAddress = customAddr,
            startedAtMs = System.currentTimeMillis(),
            activeChannelLabel = initialChannel,
            targetReceiverReady = target.keyboardActive,
        )
        activeSessionState.value = session
        errorMessageState.value = null
        val exitHint = exitShortcutOf(item).format()
        statusMessageState.value = "正在控制 ${target.displayTitle} · 按 $exitHint 退出"
        Ticker.refresh(type)

        toastOnMain("已开启远程键盘映射 → ${target.displayTitle} (按 $exitHint 或点状态栏退出)")

        // Probe direct LAN / P2P endpoints in background if mode is AUTO or DIRECT
        if (mode == RemoteKeyboardProtocol.MODE_AUTO || mode == RemoteKeyboardProtocol.MODE_DIRECT) {
            probeDirectEndpointsAsync(session)
        }
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
        }
    }

    private fun probeDirectEndpointsAsync(session: ActiveSession) {
        probeExecutor.execute {
            val candidates = ArrayList<DirectTargetEndpoint>()
            RemoteKeyboardProtocol.parseDirectEndpoint(
                session.customDirectAddress,
                session.target.directPort.takeIf { it in 1..65535 } ?: RemoteKeyboardProtocol.DEFAULT_DIRECT_PORT,
            )?.let { candidates.add(it) }
            candidates.addAll(session.target.candidateDirectEndpoints)

            for (ep in candidates) {
                if (activeSessionState.value?.target?.id != session.target.id) return@execute
                if (RemoteKeyboardClient.probeDirectTcpEndpoint(ep)) {
                    val current = activeSessionState.value ?: return@execute
                    if (current.target.id == session.target.id) {
                        activeSessionState.value = current.copy(
                            verifiedDirectEndpoint = ep,
                            activeChannelLabel = "直连 (${ep.host}:${ep.port})",
                            targetReceiverReady = true,
                        )
                        Ticker.refresh(type)
                    }
                    return@execute
                }
            }

            val current = activeSessionState.value ?: return@execute
            if (current.target.id == session.target.id && current.verifiedDirectEndpoint == null) {
                val fallbackLabel = if (current.transportMode == RemoteKeyboardProtocol.MODE_DIRECT) {
                    "直连未响应 (仍尝试 UDP)"
                } else {
                    "VPS 极速中转"
                }
                activeSessionState.value = current.copy(activeChannelLabel = fallbackLabel)
                Ticker.refresh(type)
            }
        }
    }

    private fun enqueueEvent(event: RemoteKeyboardEvent, session: ActiveSession) {
        pendingEvents.add(event)
        activeSessionState.value?.let { cur ->
            if (cur.target.id == session.target.id) {
                activeSessionState.value = cur.copy(
                    sentKeyCount = cur.sentKeyCount + 1L,
                    lastKeyLabel = "${event.keyName} (${if (event.action == "down") "↓" else "↑"})",
                )
            }
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

    private fun dispatchBatch(session: ActiveSession, batch: List<RemoteKeyboardEvent>) {
        if (batch.isEmpty()) return
        val id = identityState.value
        val req = RemoteKeyboardSendRequest(
            targetDeviceId = session.target.id,
            sourceDeviceId = id.deviceId,
            sourceName = id.displayName.ifBlank { "Googlebook" },
            events = batch,
        )
        val payloadBytes = CloudClipboardProtocol.json.encodeToString(req).toByteArray(StandardCharsets.UTF_8)

        // 1. Direct P2P / LAN path
        val directEp = session.verifiedDirectEndpoint
            ?: RemoteKeyboardProtocol.parseDirectEndpoint(session.customDirectAddress)
            ?: session.target.candidateDirectEndpoints.firstOrNull()

        if (session.transportMode == RemoteKeyboardProtocol.MODE_DIRECT) {
            if (directEp != null) {
                val ok = RemoteKeyboardClient.sendDirectUdp(directEp, payloadBytes) ||
                    RemoteKeyboardClient.sendDirectTcp(directEp, payloadBytes)
                if (!ok) {
                    errorMessageState.value = "局域网直连发送失败 (${directEp.host}:${directEp.port})"
                }
            } else {
                errorMessageState.value = "未找到目标设备的局域网 IP，请填写直连地址或切换为自动模式。"
            }
            return
        }

        // In AUTO mode, if we have a verified direct endpoint, send via UDP + TCP direct immediately;
        // if TCP direct fails, fall through to VPS relay seamlessly.
        if (session.transportMode == RemoteKeyboardProtocol.MODE_AUTO && directEp != null) {
            // Fire UDP packet immediately for sub-millisecond delivery when on same LAN / tunnel
            RemoteKeyboardClient.sendDirectUdp(directEp, payloadBytes)
            if (session.verifiedDirectEndpoint != null) {
                if (RemoteKeyboardClient.sendDirectTcp(directEp, payloadBytes)) {
                    return
                }
                // Direct TCP dropped: clear verified endpoint and fall back to VPS relay
                activeSessionState.value?.let { cur ->
                    if (cur.target.id == session.target.id) {
                        activeSessionState.value = cur.copy(
                            verifiedDirectEndpoint = null,
                            activeChannelLabel = "VPS 极速中转",
                        )
                    }
                }
            }
        }

        // 2. VPS Relay path (works across any NAT / network)
        runCatching {
            val reply = RemoteKeyboardClient.sendRelay(id, req)
            activeSessionState.value?.let { cur ->
                if (cur.target.id == session.target.id) {
                    val label = if (cur.verifiedDirectEndpoint != null) {
                        cur.activeChannelLabel
                    } else if (reply.targetReceiverReady) {
                        "VPS 极速中转"
                    } else {
                        "VPS 中转 (对端待升级接收器)"
                    }
                    activeSessionState.value = cur.copy(
                        activeChannelLabel = label,
                        targetReceiverReady = reply.targetReceiverReady,
                    )
                }
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
        if (!id.isRegistered && id.sessionCookie.isBlank()) {
            if (!quiet) {
                errorMessageState.value = "请先在设置中完成 VPS 设备注册。"
            }
            return
        }
        loadingDevicesState.value = true
        if (!quiet) errorMessageState.value = null
        deviceExecutor.execute {
            val res = runCatching { RemoteKeyboardClient.fetchDevices(id) }
            loadingDevicesState.value = false
            res.onSuccess { list ->
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
                    statusMessageState.value = "已同步 ${list.size} 台设备"
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

    private fun toastOnMain(message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(Env.app, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun state(item: ItemConfig): ItemState {
        ensureInitialized()
        val session = activeSessionState.value
        val devices = devicesState.value
        val error = errorMessageState.value
        val exitShortcut = exitShortcutOf(item)
        val showTextInBar = item.optBool("showActiveText", true)

        val barText = if (session != null && showTextInBar) {
            session.target.displayTitle.take(10)
        } else {
            null
        }

        val statusSummary = when {
            session != null -> "正在控制 ${session.target.displayTitle} (${session.activeChannelLabel}) · 按 ${exitShortcut.format()} 或点击图标退出"
            error != null -> error
            devices.isNotEmpty() -> {
                val onlineCount = devices.count { it.isDesktopTarget && it.online }
                "就绪 · $onlineCount/${devices.count { it.isDesktopTarget }} 台电脑在线"
            }
            identityState.value.isRegistered -> "就绪 · 点击选择目标电脑开启映射"
            else -> "未注册 VPS 设备"
        }

        return ItemState(
            icon = "brand:remote_keyboard",
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
            textLimit = 12,
            widthKey = if (session != null) "rk_active" else null,
        )
    }

    private fun stringResourceSafe(resId: Int, fallback: String): String =
        runCatching { Env.app.getString(resId) }.getOrDefault(fallback)

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        ensureInitialized()
        val id by identityState.collectAsState()
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
            if (id.isRegistered || id.sessionCookie.isNotBlank()) {
                refreshDevices(quiet = true)
            }
        }

        val subtitle = when {
            session != null -> "映射中 → ${session!!.target.displayTitle} · ${session!!.activeChannelLabel}"
            loading -> "正在从 vps.why.band 同步设备列表…"
            id.isRegistered -> "已连接 (${id.displayName.ifBlank { id.deviceId }}) · 退出键 ${exitShortcut.format()}"
            else -> "未注册设备 · 请先在设置或云剪切板中绑定 VPS"
        }

        MenuCard(
            icon = "brand:remote_keyboard",
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
                                    append(active.activeChannelLabel)
                                    append(" · 已发 ")
                                    append(active.sentKeyCount)
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
                            text = "提示：请确认目标 Windows 电脑已运行最新版 SafetyGuard（并在「云剪切板 › 远程键盘映射接收」中保持开启）。",
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
            val modeLabels = listOf("自动 (优先直连/打洞，回退 VPS)", "仅 VPS 中转", "仅局域网直连")
            val modeValues = listOf(RemoteKeyboardProtocol.MODE_AUTO, RemoteKeyboardProtocol.MODE_RELAY, RemoteKeyboardProtocol.MODE_DIRECT)
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
            val desktopDevices = devices.filter { it.isDesktopTarget }
            SectionLabel("目标电脑列表 (${desktopDevices.size})")

            if (desktopDevices.isNotEmpty()) {
                desktopDevices.forEach { dev ->
                    val isSelected = selectedTarget?.id == dev.id
                    val isCurrentlyMapped = session?.target?.id == dev.id
                    val lanIp = dev.lanIpv4Addresses.firstOrNull()
                    val statusTag = buildString {
                        append(dev.platformLabel)
                        append(if (dev.online) " · 在线" else " · 离线")
                        if (dev.keyboardActive) append(" · 接收就绪")
                        if (!lanIp.isNullOrBlank()) append(" · LAN $lanIp")
                        else if (dev.sourceIp.isNotBlank()) append(" · ${dev.sourceIp}")
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
                    if (id.isRegistered) {
                        "暂未发现其他桌面设备。请点击右上角刷新，或确认对端电脑已在 vps.why.band 注册。"
                    } else {
                        "尚未连接云服务器。请打开下方设置填写面板地址与注册码（与云剪切板共用同一设备凭证）。"
                    }
                )
            }

            MenuDivider()
            MenuEntry(
                icon = Sym.CONTENT_COPY,
                label = "复制 Windows 接收端一键升级命令",
                sub = "在目标 Windows 电脑的管理员 PowerShell 中运行一次即可启用键盘接收与局域网直连",
            ) {
                val baseUrl = id.vpsUrl.ifBlank { CloudClipboardProtocol.DEFAULT_VPS_URL }.trimEnd('/')
                val cmd = "irm $baseUrl/download/update-keyboard-windows.ps1 | iex"
                runCatching {
                    val cm = Env.app.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("NetTransfer Keyboard Update", cmd))
                }
                // Also push to Cloud Clipboard if registered so the user can paste it right on Windows!
                if (id.isRegistered) {
                    deviceExecutor.execute {
                        runCatching { CloudClipboardClient.pushText(id, cmd) }
                    }
                    toastOnMain("已复制升级命令并同步到云剪切板，可在 Windows 直接粘贴运行")
                } else {
                    toastOnMain("已复制 Windows 升级命令到本机剪贴板")
                }
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
        val devices by devicesState.collectAsState()
        val loading by loadingDevicesState.collectAsState()
        val statusMsg by statusMessageState.collectAsState()
        val errorMsg by errorMessageState.collectAsState()

        val currentExitShortcut = exitShortcutOf(item)
        var recordingShortcut by remember { mutableStateOf(false) }
        val captureFocus = remember { FocusRequester() }

        LaunchedEffect(recordingShortcut) {
            if (recordingShortcut) {
                runCatching { captureFocus.requestFocus() }
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
            InfoRow(
                label = "VPS 凭证状态",
                value = if (id.isRegistered) "已注册 (${id.displayName} · ID ${id.deviceId.take(10)})" else "未注册设备",
                valueColor = if (id.isRegistered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(
                label = "发现的桌面设备",
                value = "${devices.count { it.isDesktopTarget }} 台 (${devices.count { it.isDesktopTarget && it.online }} 台在线)",
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

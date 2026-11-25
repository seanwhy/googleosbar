package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.SmallIconButton
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executors

data class TailnetUiSnapshot(
    val loading: Boolean = false,
    val acting: Boolean = false,
    val vpsSummary: TailnetSummaryResponse? = null,
    val vpsError: String? = null,
    val vmStatus: VmTailnetStatusResponse? = null,
    val vmReachable: Boolean = false,
    val actionMessage: String? = null,
    val actionOk: Boolean = true,
    val updateReply: VmNetTransferActionReply? = null,
    val identityRegistered: Boolean = false,
    val dashboardUrl: String = CloudClipboardProtocol.DEFAULT_VPS_URL,
    val updatedAtMs: Long = 0L,
)

object TailnetController {
    private const val POLL_INTERVAL_MS = 30_000L
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BentoBar-Tailnet").apply { isDaemon = true }
    }
    private val _state = MutableStateFlow(TailnetUiSnapshot())
    val state: StateFlow<TailnetUiSnapshot> = _state.asStateFlow()

    @Volatile private var live = false
    @Volatile private var lastStartedMs = 0L
    @Volatile private var inFlightRefresh = false

    fun onLive() {
        live = true
        maybeRefresh(force = true)
    }

    fun onIdle() {
        live = false
    }

    fun tick(nowMs: Long) {
        if (!live) return
        if (nowMs - lastStartedMs >= POLL_INTERVAL_MS) {
            maybeRefresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) {
        maybeRefresh(force = force)
    }

    private fun maybeRefresh(force: Boolean) {
        if (inFlightRefresh && !force) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStartedMs < 3_000L) return
        lastStartedMs = now
        inFlightRefresh = true
        _state.value = _state.value.copy(loading = true)
        executor.execute {
            try {
                val snap = collectSnapshot(
                    previous = _state.value,
                    actionMessage = _state.value.actionMessage,
                    actionOk = _state.value.actionOk,
                    updateReply = _state.value.updateReply,
                )
                _state.value = snap
                runCatching { Ticker.refresh("tailnet") }
            } finally {
                inFlightRefresh = false
            }
        }
    }

    fun runAction(action: String, code: String? = null) {
        if (_state.value.acting) return
        _state.value = _state.value.copy(
            acting = true,
            actionMessage = "正在执行 $action...",
            actionOk = true,
        )
        executor.execute {
            try {
                val timeoutMs = if (action == "upgrade" || action == "enroll") 90_000 else 30_000
                val reply = TailnetVmBridge.postAction(action = action, code = code, timeoutMs = timeoutMs)
                val nextUpdate = if (action == "check_update") reply else _state.value.updateReply
                val fresh = collectSnapshot(
                    previous = _state.value,
                    actionMessage = reply.message.ifBlank { if (reply.ok) "操作成功" else "操作失败" },
                    actionOk = reply.ok,
                    updateReply = nextUpdate,
                )
                _state.value = fresh.copy(acting = false)
                runCatching { Ticker.refresh("tailnet") }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    acting = false,
                    actionOk = false,
                    actionMessage = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    private fun collectSnapshot(
        previous: TailnetUiSnapshot,
        actionMessage: String?,
        actionOk: Boolean,
        updateReply: VmNetTransferActionReply?,
    ): TailnetUiSnapshot {
        val vm = runCatching { TailnetVmBridge.fetchStatus() }.getOrNull()
        val identity = runCatching { CloudClipboardStore.loadIdentity() }.getOrElse { NetTransferIdentity() }
        var vpsSummary: TailnetSummaryResponse? = previous.vpsSummary
        var vpsError: String? = null
        if (identity.isRegistered) {
            runCatching { CloudClipboardClient.fetchTailnetSummary(identity) }
                .onSuccess { vpsSummary = it }
                .onFailure { vpsError = it.message ?: it.javaClass.simpleName }
        }
        val baseDashboard = (vpsSummary?.dashboardUrl?.takeIf { it.isNotBlank() }
            ?: vm?.agent?.vpsUrl?.takeIf { it.isNotBlank() }
            ?: identity.vpsUrl.takeIf { it.isNotBlank() }
            ?: CloudClipboardProtocol.DEFAULT_VPS_URL).trimEnd('/')
        return TailnetUiSnapshot(
            loading = false,
            acting = false,
            vpsSummary = vpsSummary,
            vpsError = vpsError,
            vmStatus = vm,
            vmReachable = vm != null,
            actionMessage = actionMessage,
            actionOk = actionOk,
            updateReply = updateReply,
            identityRegistered = identity.isRegistered,
            dashboardUrl = baseDashboard,
            updatedAtMs = System.currentTimeMillis(),
        )
    }
}

object TailnetItem : ItemType("tailnet", R.string.item_tailnet_title, "brand:tailscale", R.string.item_tailnet_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 360
    override val refreshMs = 5_000L
    override val canBeActive = true
    override val trigger = Trigger(R.string.trigger_tailnet, R.string.trigger_tailnet_short)

    override fun onLive() {
        TailnetController.onLive()
    }

    override fun onIdle() {
        TailnetController.onIdle()
    }

    override fun sample(now: Long) {
        TailnetController.tick(System.currentTimeMillis())
    }

    override fun menuOpening(item: ItemConfig) {
        TailnetController.refresh(force = true)
    }

    override fun state(item: ItemConfig): ItemState = buildState(TailnetController.state.value)

    internal fun buildState(snap: TailnetUiSnapshot): ItemState {
        val vps = snap.vpsSummary
        val vmTailnet = snap.vmStatus?.tailnet
        val agent = snap.vmStatus?.agent

        val online = when {
            vps != null -> vps.onlineNodes
            vmTailnet != null && vmTailnet.available -> vmTailnet.onlineNodes
            else -> -1
        }
        val total = when {
            vps != null -> vps.totalNodes
            vmTailnet != null && vmTailnet.available -> vmTailnet.totalNodes
            else -> -1
        }
        val agentAlert = snap.vmReachable && agent != null && agent.installed && !agent.active
        val netAlert = (vps?.alertCount ?: 0) > 0
        val hasAlert = agentAlert || netAlert

        val text = when {
            online >= 0 && total > 0 -> "$online/$total"
            snap.loading -> "…"
            !snap.vmReachable && vps == null -> "离线"
            else -> "--"
        }
        val tone = when {
            hasAlert -> Tone.ALERT
            !snap.vmReachable && vps == null -> Tone.WARN
            else -> Tone.NORMAL
        }
        val agentDesc = when {
            !snap.vmReachable -> "VM 未连接"
            agent == null -> "未知"
            !agent.installed -> "NetTransfer 未安装"
            agent.active -> "NetTransfer ${agent.version.ifBlank { "运行中" }}"
            else -> "NetTransfer 已停止 (${agent.state})"
        }
        val tailnetDesc = if (online >= 0 && total >= 0) "$online/$total 节点在线" else "Tailnet 未就绪"
        val tip = buildString {
            append("Tailscale ")
            append(tailnetDesc)
            append(" · ")
            append(agentDesc)
            vps?.topAlert?.takeIf { it.isNotBlank() }?.let {
                append("\n告警: ")
                append(it)
            }
        }
        return ItemState(
            icon = icon,
            filled = online > 0 || (agent?.active == true),
            text = text,
            desc = "Tailscale $tailnetDesc，$agentDesc",
            active = hasAlert || (!snap.vmReachable && vps == null),
            tone = tone,
            tooltip = tip,
            textLimit = 10,
        )
    }

    override val menu: (@Composable (ItemConfig, MenuHost) -> Unit) = { _, host ->
        val snap by TailnetController.state.collectAsState()
        var showAllNodes by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        var enrollCode by remember { mutableStateOf("") }

        val vps = snap.vpsSummary
        val vmTailnet = snap.vmStatus?.tailnet
        val onlineCount = vps?.onlineNodes ?: vmTailnet?.onlineNodes ?: 0
        val totalCount = vps?.totalNodes ?: vmTailnet?.totalNodes ?: 0
        val directRatioPct = ((vps?.directRatio ?: vmTailnet?.directRatio ?: 0.0) * 100.0).toInt()
        val primaryDerp = (vps?.primaryDerp ?: vmTailnet?.primaryDerp).orEmpty().ifBlank { "--" }
        val derpMs = vps?.primaryDerpMs?.let { String.format(Locale.US, "%.1f ms", it) }

        val subtitle = when {
            totalCount > 0 -> "$onlineCount/$totalCount 在线 · 直连 $directRatioPct% · 主 DERP $primaryDerp" +
                (derpMs?.let { " ($it)" } ?: "")
            snap.loading -> "正在刷新 Tailscale 与本机服务状态…"
            else -> "Tailscale 与 NetTransfer 控制台"
        }

        MenuCard(
            icon = icon,
            title = "Tailscale & NetTransfer",
            subtitle = subtitle,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallIconButton(
                        sym = Sym.REFRESH,
                        label = "刷新",
                        enabled = !snap.loading && !snap.acting,
                        onClick = { TailnetController.refresh(force = true) },
                    )
                    SmallIconButton(
                        sym = Sym.LAN,
                        label = "打开 Tailnet 大屏",
                        onClick = {
                            openExternalUrl("${snap.dashboardUrl.trimEnd('/')}/tailnet")
                            host.close()
                        },
                    )
                }
            },
        ) {
            vps?.topAlert?.takeIf { it.isNotBlank() }?.let { alert ->
                MenuNote("告警：${vps.alertCount} 项 — $alert")
            }
            snap.vpsError?.takeIf { it.isNotBlank() }?.let { err ->
                MenuNote("云端摘要异常：$err")
            }

            // 1. Tailscale Nodes
            val displayNodes = buildDisplayNodes(vps, vmTailnet)
            SectionLabel("Tailscale 节点 (${displayNodes.size})")
            if (displayNodes.isEmpty()) {
                MenuNote(
                    if (!snap.identityRegistered && !snap.vmReachable) {
                        "尚未配置 NetTransfer 身份且未连接本地 Linux 守护进程。"
                    } else {
                        "暂无可显示的节点信息。"
                    }
                )
            } else {
                val visible = if (showAllNodes) displayNodes else displayNodes.take(6)
                visible.forEach { node ->
                    val nameLabel = node.displayName.ifBlank { node.name }.ifBlank { node.ip }
                    val modeText = when {
                        !node.online -> "离线"
                        node.mode.equals("direct", ignoreCase = true) -> {
                            node.rttMs?.let { "${it.toInt()}ms · 直连" } ?: "直连"
                        }
                        node.mode.isNotBlank() -> {
                            val via = if (node.relay.isNotBlank()) "${node.mode}(${node.relay})" else node.mode
                            node.rttMs?.let { "${it.toInt()}ms · $via" } ?: via
                        }
                        else -> "在线"
                    }
                    val subLine = buildString {
                        if (node.ip.isNotBlank()) append(node.ip)
                        if (node.os.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(node.os)
                        }
                        if (node.serviceAlert) {
                            if (isNotEmpty()) append(" · ")
                            append("服务异常")
                        }
                    }.ifBlank { null }
                    MenuEntry(
                        icon = if (node.online) Sym.CHECK_CIRCLE else Sym.CLOUD_OFF,
                        label = nameLabel,
                        sub = subLine,
                        detail = modeText,
                        trailing = if (node.ip.isNotBlank()) {
                            {
                                SmallIconButton(
                                    sym = Sym.CONTENT_COPY,
                                    label = "复制 IP",
                                    onClick = {
                                        Env.copy(node.ip)
                                        Toast.makeText(Env.app, "已复制 ${node.ip}", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        } else null,
                        onClick = {
                            val targetUrl = node.sshUrl.takeIf { it.isNotBlank() }?.let { path ->
                                if (path.startsWith("http")) path else "${snap.dashboardUrl.trimEnd('/')}$path"
                            } ?: "${snap.dashboardUrl.trimEnd('/')}/tailnet"
                            openExternalUrl(targetUrl)
                            host.close()
                        },
                    )
                }
                if (displayNodes.size > 6) {
                    TextButton(
                        onClick = { showAllNodes = !showAllNodes },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showAllNodes) "收起节点列表" else "展开全部 ${displayNodes.size} 个节点")
                    }
                }
            }

            MenuDivider()

            // 2. Local NetTransfer Daemon Management
            SectionLabel("本机 NetTransfer 后台")
            val agent = snap.vmStatus?.agent
            if (!snap.vmReachable || agent == null) {
                MenuNote("未检测到本机 Linux 守护进程 (port 19876)。请确认 Debian Terminal 正在运行。")
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = {
                        AiUsageReader.launchTerminalNow()
                        host.close()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("启动 Linux 终端并重试")
                }
            } else {
                val stateLabel = when {
                    !agent.installed -> "未安装"
                    agent.active -> if (agent.pid > 0) "运行中 (PID ${agent.pid})" else "运行中"
                    else -> "已停止 (${agent.state})"
                }
                InfoRow(
                    "运行状态",
                    stateLabel,
                    if (agent.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                InfoRow(
                    "版本 / 架构",
                    "${agent.version.ifBlank { "--" }} / ${agent.arch.ifBlank { "--" }}",
                )
                InfoRow(
                    "注册状态",
                    when {
                        agent.revoked -> "已吊销"
                        agent.registered -> "已注册 (${agent.deviceId.take(8)}…)"
                        else -> "未注册"
                    },
                    if (agent.registered && !agent.revoked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )

                snap.updateReply?.let { up ->
                    val updateLine = when {
                        up.latestVersion.isNotBlank() && up.hasUpdate ->
                            "发现新版本 ${up.latestVersion} (当前 ${up.currentVersion})"
                        up.latestVersion.isNotBlank() ->
                            "已是最新 v${up.latestVersion}"
                        else -> up.message
                    }
                    if (updateLine.isNotBlank()) {
                        MenuNote(updateLine)
                    }
                }
                snap.actionMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (snap.actionOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = { TailnetController.runAction(if (agent.active) "stop" else "start") },
                        enabled = !snap.acting && agent.binaryExists,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (agent.active) "停止" else "启动", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { TailnetController.runAction("restart") },
                        enabled = !snap.acting && agent.binaryExists,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重启", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { TailnetController.runAction("check_update") },
                        enabled = !snap.acting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("检查更新", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { TailnetController.runAction("upgrade") },
                        enabled = !snap.acting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("一键升级", maxLines = 1)
                    }
                    if (agent.hasBackup) {
                        OutlinedButton(
                            onClick = { TailnetController.runAction("rollback") },
                            enabled = !snap.acting,
                        ) {
                            Text("回滚", maxLines = 1)
                        }
                    }
                }

                if (!agent.registered || agent.revoked) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = enrollCode,
                            onValueChange = { enrollCode = it },
                            label = { Text("输入面板注册码") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = {
                                val code = enrollCode.trim()
                                if (code.isNotEmpty()) {
                                    TailnetController.runAction("enroll", code)
                                }
                            },
                            enabled = !snap.acting && enrollCode.trim().isNotEmpty(),
                        ) {
                            Text("注册")
                        }
                    }
                }

                if (agent.recentLogs.isNotEmpty()) {
                    TextButton(
                        onClick = { showLogs = !showLogs },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showLogs) "隐藏运行日志" else "查看最近日志 (${agent.recentLogs.size})")
                    }
                    if (showLogs) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            agent.recentLogs.takeLast(8).forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openExternalUrl(rawUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Env.launch(intent)
    }

    internal data class DisplayNode(
        val id: String,
        val name: String,
        val displayName: String,
        val ip: String,
        val os: String,
        val online: Boolean,
        val rttMs: Double?,
        val mode: String,
        val relay: String,
        val serviceAlert: Boolean,
        val sshUrl: String,
    )

    internal fun buildDisplayNodes(
        vps: TailnetSummaryResponse?,
        vm: VmTailnetFallback?,
    ): List<DisplayNode> {
        val list = if (vps != null && vps.nodes.isNotEmpty()) {
            vps.nodes.map {
                DisplayNode(
                    id = it.id,
                    name = it.name,
                    displayName = it.displayName,
                    ip = it.ip,
                    os = it.os,
                    online = it.online,
                    rttMs = it.rttMs,
                    mode = it.mode,
                    relay = it.relay,
                    serviceAlert = it.serviceAlert,
                    sshUrl = it.sshUrl,
                )
            }
        } else if (vm != null && vm.nodes.isNotEmpty()) {
            vm.nodes.map {
                DisplayNode(
                    id = it.id,
                    name = it.name,
                    displayName = it.displayName,
                    ip = it.ip,
                    os = it.os,
                    online = it.online,
                    rttMs = null,
                    mode = it.mode,
                    relay = it.relay,
                    serviceAlert = false,
                    sshUrl = "",
                )
            }
        } else {
            emptyList()
        }
        return list.sortedWith(
            compareByDescending<DisplayNode> { it.online }
                .thenByDescending { it.serviceAlert }
                .thenBy { it.displayName.ifBlank { it.name }.lowercase(Locale.US) }
        )
    }
}

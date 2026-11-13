package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

private fun launchAppOrUrl(packages: List<String>, fallbackUrl: String) {
    val pm = Env.app.packageManager
    for (pkg in packages) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null && Env.launch(launchIntent, quiet = true)) {
            return
        }
    }
    Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
}

private data class QuotaBarValues(
    val outerPercent: Int,
    val innerPercent: Int?,
    val usedMax: Float,
    val summary: String,
)

private fun problemLabel(problem: QuotaProblem): String = when (problem) {
    QuotaProblem.TIMEOUT -> "网络请求超时"
    QuotaProblem.ERROR -> "网络异常 / 获取失败"
    QuotaProblem.STALE -> "数据已过期（超时未更新）"
    QuotaProblem.UNREACHABLE -> "无法连接用量守护进程"
    QuotaProblem.UNCONFIGURED -> "未配置凭证"
}

private fun formatRecordAge(epochSec: Long, nowMs: Long, h24: Boolean): String {
    val atMs = epochSec * 1000L
    val clock = Dates.format(Dates.timeSkeleton(h24, seconds = true), atMs)
    val ageMs = (nowMs - atMs).coerceAtLeast(0L)
    return "$clock (${Fmt.duration(ageMs)}前)"
}

private fun <T> buildQuotaState(
    icon: String,
    widthKey: String,
    providerTitle: String,
    resolved: ResolvedQuota<T>,
    threshold: Float,
    extract: (T) -> QuotaBarValues,
    lastSummary: (T) -> String,
): ItemState {
    val live = resolved.live
    if (live != null) {
        val v = extract(live)
        return ItemState(
            icon = icon,
            text = "${v.outerPercent}%",
            quotaRing = QuotaRing(outerPercent = v.outerPercent, innerPercent = v.innerPercent, status = QuotaRingStatus.NORMAL),
            active = v.usedMax >= threshold,
            tone = Tone.NORMAL,
            widthKey = widthKey,
            desc = "$providerTitle: ${v.summary}",
            tooltip = "$providerTitle · ${v.summary}",
        )
    }

    val problem = resolved.problem ?: QuotaProblem.ERROR
    val ringStatus = if (problem == QuotaProblem.UNCONFIGURED) QuotaRingStatus.UNAVAILABLE else QuotaRingStatus.TIMEOUT
    val shortText = if (problem == QuotaProblem.UNCONFIGURED) "--" else "TO"
    val problemText = problemLabel(problem)
    val cachedNote = resolved.lastOk?.let { " · 上次 ${lastSummary(it)}" }.orEmpty()
    val fullDesc = "$providerTitle: $problemText$cachedNote"

    return ItemState(
        icon = icon,
        text = shortText,
        label = "$providerTitle ${if (problem == QuotaProblem.UNCONFIGURED) "未配置" else "超时"}",
        quotaRing = QuotaRing(status = ringStatus),
        active = false,
        tone = Tone.NORMAL,
        widthKey = widthKey,
        desc = fullDesc,
        tooltip = fullDesc,
    )
}

private class AiUsageStaging(
    type: String,
    private val buildSample: (mode: String, nowSec: Long) -> AiUsageSnapshot?,
) {
    private val staged = Staged<AiUsageSnapshot>(type)
    val stages = listOf("stage timeout", "stage timeout-cached", "stage ok")

    fun of(item: ItemConfig): AiUsageSnapshot? = staged.of(item)

    fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        "stage" -> {
            val mode = args.getOrNull(1).orEmpty()
            val sample = buildSample(mode, Now.wall() / 1000L)
            if (sample == null) null else {
                staged.value = sample
                Ticker.refresh()
                "staged $mode"
            }
        }
        "off" -> {
            staged.value = null
            Ticker.refresh()
            "staging off"
        }
        else -> null
    }
}

@Composable
private fun QuotaBucketMeter(
    label: String,
    remainingPct: Int,
    fraction: Float,
    countdown: String,
    usedPct: Float,
    threshold: Float,
    historical: Boolean,
    normalBarColor: Color = MaterialTheme.colorScheme.primary,
) {
    val isCrit = !historical && usedPct >= threshold
    val valueColor = when {
        historical -> MaterialTheme.colorScheme.onSurfaceVariant
        isCrit -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val barColor = when {
        historical -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        isCrit -> MaterialTheme.colorScheme.error
        else -> normalBarColor
    }
    InfoRow(label, "$remainingPct% · $countdown", valueColor)
    Meter(fraction, barColor)
}

@Composable
private fun <T> QuotaProblemSection(
    resolved: ResolvedQuota<T>,
    nowMs: Long,
    renderCached: @Composable (T) -> Unit,
) {
    val problem = resolved.problem ?: QuotaProblem.ERROR
    val statusColor = if (problem == QuotaProblem.UNCONFIGURED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    InfoRow(stringResource(R.string.ai_usage_status), problemLabel(problem), statusColor)

    resolved.errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
        MenuNote("原因: $msg")
    }
    if (problem == QuotaProblem.STALE && resolved.snapshotUpdatedAtSec != null) {
        val lagMs = (nowMs - resolved.snapshotUpdatedAtSec * 1000L).coerceAtLeast(0L)
        MenuNote("快照已滞后 ${Fmt.duration(lagMs)} 未更新")
    }

    val lastOk = resolved.lastOk
    if (lastOk != null) {
        val h24 = DateFormat.is24HourFormat(LocalContext.current)
        val timeDesc = resolved.lastOkAtSec?.let { formatRecordAge(it, nowMs, h24) }
        MenuDivider()
        SectionLabel(if (timeDesc != null) "上次成功记录 · $timeDesc" else "上次成功记录")
        renderCached(lastOk)
    } else if (problem != QuotaProblem.UNCONFIGURED) {
        Spacer(Modifier.height(4.dp))
        MenuNote("暂无历史成功记录，请检查网络或代理设置后重试")
    }
}

@Composable
private fun LiveUpdatedNote(epochSec: Long?, nowMs: Long) {
    if (epochSec == null || epochSec <= 0L) return
    val h24 = DateFormat.is24HourFormat(LocalContext.current)
    Spacer(Modifier.height(6.dp))
    MenuNote("最近更新: ${formatRecordAge(epochSec, nowMs, h24)}")
}

@Composable
private fun AntigravityDetails(ag: AntigravityQuota, threshold: Float, historical: Boolean) {
    SectionLabel(if (historical) "Gemini Models (缓存)" else "Gemini Models (圆环主数据源)")
    ag.gemini5h?.let { b ->
        QuotaBucketMeter(
            label = "5-Hour Remaining (内环)",
            remainingPct = b.remaining.toInt(),
            fraction = b.fraction,
            countdown = b.formattedCountdown(),
            usedPct = b.usedPercent ?: 0f,
            threshold = threshold,
            historical = historical,
            normalBarColor = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(4.dp))
    ag.geminiWeekly?.let { b ->
        QuotaBucketMeter(
            label = "Weekly Remaining (外环/圆心)",
            remainingPct = b.remaining.toInt(),
            fraction = b.fraction,
            countdown = b.formattedCountdown(),
            usedPct = b.usedPercent ?: 0f,
            threshold = threshold,
            historical = historical,
            normalBarColor = MaterialTheme.colorScheme.secondary,
        )
    }
    if (ag.claude5h != null || ag.claudeWeekly != null) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Claude & GPT Models")
        ag.claude5h?.let { c5 ->
            QuotaBucketMeter(
                label = "5-Hour Remaining",
                remainingPct = c5.remaining.toInt(),
                fraction = c5.fraction,
                countdown = c5.formattedCountdown(),
                usedPct = c5.usedPercent ?: 0f,
                threshold = threshold,
                historical = historical,
                normalBarColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
        }
        ag.claudeWeekly?.let { cw ->
            QuotaBucketMeter(
                label = "Weekly Remaining",
                remainingPct = cw.remaining.toInt(),
                fraction = cw.fraction,
                countdown = cw.formattedCountdown(),
                usedPct = cw.usedPercent ?: 0f,
                threshold = threshold,
                historical = historical,
                normalBarColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun CodexDetails(cx: CodexQuota, threshold: Float, historical: Boolean) {
    cx.window5h?.let { w ->
        QuotaBucketMeter(
            label = "5-Hour Remaining (内环)",
            remainingPct = w.remaining.toInt(),
            fraction = w.fraction,
            countdown = w.formattedCountdown(),
            usedPct = w.usedPercent ?: 0f,
            threshold = threshold,
            historical = historical,
            normalBarColor = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(4.dp))
    cx.windowWeekly?.let { w ->
        QuotaBucketMeter(
            label = "Weekly Remaining (外环/圆心)",
            remainingPct = w.remaining.toInt(),
            fraction = w.fraction,
            countdown = w.formattedCountdown(),
            usedPct = w.usedPercent ?: 0f,
            threshold = threshold,
            historical = historical,
            normalBarColor = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun GrokCycleDetails(
    cycle: GrokCycle,
    threshold: Float,
    historical: Boolean,
) {
    val rem = cycle.remaining.toInt()
    val used = cycle.usedPercent?.toInt() ?: (100 - rem)
    val isCrit = !historical && (cycle.usedPercent ?: 0f) >= threshold
    val valueColor = when {
        historical -> MaterialTheme.colorScheme.onSurfaceVariant
        isCrit -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val barColor = when {
        historical -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        isCrit -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    InfoRow("Weekly Remaining", "$rem% (已用 $used%) · ${cycle.formattedCountdown()}", valueColor)
    Meter(cycle.fraction, barColor)
}

/**
 * 1. Independent Antigravity Item (Official A-Arch icon + Concentric 5h/Weekly Dual-Ring with centered remaining %)
 */
object AntigravityItem : ItemType("ai_antigravity", R.string.item_ai_antigravity_title, "brand:antigravity", R.string.item_ai_antigravity_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 320
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    private val sampleOk = AntigravityQuota(
        status = "ok",
        gemini5h = QuotaBucket(usedPercent = 12f, remainingPercent = 88f, resetInSec = 14400L),
        geminiWeekly = QuotaBucket(usedPercent = 26f, remainingPercent = 74f, resetInSec = 259200L),
    )

    private val staging = AiUsageStaging(type) { mode, nowSec ->
        when (mode) {
            "ok" -> AiUsageSnapshot(updatedAt = nowSec, antigravity = sampleOk.copy(fetchedAt = nowSec, lastOkAt = nowSec))
            "timeout" -> AiUsageSnapshot(updatedAt = nowSec, antigravity = AntigravityQuota(status = "timeout", error = "Request timed out (staged)"))
            "timeout-cached" -> AiUsageSnapshot(
                updatedAt = nowSec,
                antigravity = AntigravityQuota(status = "timeout", error = "Request timed out (staged)", lastOkAt = nowSec - 420L, lastOk = sampleOk),
            )
            else -> null
        }
    }

    override val stages get() = staging.stages
    override fun debug(args: List<String>) = staging.debug(args)

    private fun resolve(item: ItemConfig, nowMs: Long): ResolvedQuota<AntigravityQuota> {
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        return AiUsageReader.resolveAntigravity(nowMs, customPath, staging.of(item))
    }

    override fun state(item: ItemConfig): ItemState {
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        return buildQuotaState(
            icon = "brand:antigravity",
            widthKey = "ai_antigravity",
            providerTitle = "Antigravity",
            resolved = resolve(item, nowMs),
            threshold = threshold,
            extract = { ag ->
                val g5hRem = ag.gemini5h?.remaining?.toInt() ?: 100
                val gWRem = ag.geminiWeekly?.remaining?.toInt() ?: 100
                val usedMax = maxOf(ag.gemini5h?.usedPercent ?: 0f, ag.geminiWeekly?.usedPercent ?: 0f)
                QuotaBarValues(gWRem, g5hRem, usedMax, "5h ${g5hRem}% · Weekly ${gWRem}%")
            },
            lastSummary = { ag -> "${ag.geminiWeekly?.remaining?.toInt() ?: ag.gemini5h?.remaining?.toInt() ?: 0}%" },
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        val resolved = resolve(item, nowMs)

        MenuCard(
            "brand:antigravity",
            stringResource(R.string.item_ai_antigravity_title),
            subtitle = "Gemini Models · 5h & Weekly Quotas"
        ) {
            val live = resolved.live
            if (live != null) {
                AntigravityDetails(live, threshold, historical = false)
                LiveUpdatedNote(resolved.lastOkAtSec ?: resolved.snapshotUpdatedAtSec, nowMs)
            } else {
                QuotaProblemSection(resolved, nowMs) { cached ->
                    AntigravityDetails(cached, threshold, historical = true)
                }
            }
            MenuDivider()
            MenuEntry(Sym.REFRESH, "立即重试获取") {
                AiUsageReader.forceRefresh()
            }
            MenuEntry(Sym.OPEN_IN_NEW, "打开 Antigravity") {
                host.close()
                launchAppOrUrl(
                    listOf("com.google.android.apps.antigravity", "com.google.antigravity", "com.android.virtualization.terminal"),
                    "https://idx.google.com"
                )
            }
            MenuEntry(Sym.SETTINGS, stringResource(R.string.context_item_settings)) {
                host.openItemSettings(item.id)
            }
        }
    }
}

/**
 * 2. Independent Codex Item (Official OpenAI 6-petal Blossom icon + Concentric 5h/Weekly Dual-Ring with centered remaining %)
 */
object CodexItem : ItemType("ai_codex", R.string.item_ai_codex_title, "brand:codex", R.string.item_ai_codex_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 320
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    private val sampleOk = CodexQuota(
        status = "ok",
        planType = "plus",
        window5h = CodexWindow(usedPercent = 20f, remainingPercent = 80f, resetInSec = 7200L),
        windowWeekly = CodexWindow(usedPercent = 35f, remainingPercent = 65f, resetInSec = 259200L),
    )

    private val staging = AiUsageStaging(type) { mode, nowSec ->
        when (mode) {
            "ok" -> AiUsageSnapshot(updatedAt = nowSec, codex = sampleOk.copy(fetchedAt = nowSec, lastOkAt = nowSec))
            "timeout" -> AiUsageSnapshot(updatedAt = nowSec, codex = CodexQuota(status = "timeout", error = "Request timed out (staged)"))
            "timeout-cached" -> AiUsageSnapshot(
                updatedAt = nowSec,
                codex = CodexQuota(status = "timeout", error = "Request timed out (staged)", lastOkAt = nowSec - 420L, lastOk = sampleOk),
            )
            else -> null
        }
    }

    override val stages get() = staging.stages
    override fun debug(args: List<String>) = staging.debug(args)

    private fun resolve(item: ItemConfig, nowMs: Long): ResolvedQuota<CodexQuota> {
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        return AiUsageReader.resolveCodex(nowMs, customPath, staging.of(item))
    }

    override fun state(item: ItemConfig): ItemState {
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        return buildQuotaState(
            icon = "brand:codex",
            widthKey = "ai_codex",
            providerTitle = "Codex",
            resolved = resolve(item, nowMs),
            threshold = threshold,
            extract = { cx ->
                val cx5hRem = cx.window5h?.remaining?.toInt() ?: 100
                val cxWRem = cx.windowWeekly?.remaining?.toInt() ?: cx5hRem
                val usedMax = maxOf(cx.window5h?.usedPercent ?: 0f, cx.windowWeekly?.usedPercent ?: 0f)
                QuotaBarValues(cxWRem, cx5hRem, usedMax, "5h ${cx5hRem}% · Weekly ${cxWRem}%")
            },
            lastSummary = { cx -> "${cx.windowWeekly?.remaining?.toInt() ?: cx.window5h?.remaining?.toInt() ?: 0}%" },
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        val resolved = resolve(item, nowMs)
        val plan = (resolved.live?.planType ?: resolved.lastOk?.planType ?: "PLUS").uppercase()

        MenuCard(
            "brand:codex",
            stringResource(R.string.item_ai_codex_title),
            subtitle = "OpenAI ChatGPT $plan · 5h & Weekly Quotas"
        ) {
            val live = resolved.live
            if (live != null) {
                CodexDetails(live, threshold, historical = false)
                LiveUpdatedNote(resolved.lastOkAtSec ?: resolved.snapshotUpdatedAtSec, nowMs)
            } else {
                QuotaProblemSection(resolved, nowMs) { cached ->
                    CodexDetails(cached, threshold, historical = true)
                }
            }
            MenuDivider()
            MenuEntry(Sym.REFRESH, "立即重试获取") {
                AiUsageReader.forceRefresh()
            }
            MenuEntry(Sym.OPEN_IN_NEW, "打开 Codex / ChatGPT") {
                host.close()
                launchAppOrUrl(
                    listOf("com.openai.chatgpt"),
                    "https://chatgpt.com/codex"
                )
            }
            MenuEntry(Sym.SETTINGS, stringResource(R.string.context_item_settings)) {
                host.openItemSettings(item.id)
            }
        }
    }
}

/**
 * 3. Independent Grok Item (Grok Orbital Slash icon + Single Weekly Remaining Ring with centered %)
 */
object GrokItem : ItemType("ai_grok", R.string.item_ai_grok_title, "brand:grok", R.string.item_ai_grok_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 320
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    private val sampleOk = GrokQuota(
        status = "ok",
        tier = "GrokPro",
        cycle = GrokCycle(usedPercent = 24f, remainingPercent = 76f, resetInSec = 259200L),
    )

    private val staging = AiUsageStaging(type) { mode, nowSec ->
        when (mode) {
            "ok" -> AiUsageSnapshot(updatedAt = nowSec, grok = sampleOk.copy(fetchedAt = nowSec, lastOkAt = nowSec))
            "timeout" -> AiUsageSnapshot(updatedAt = nowSec, grok = GrokQuota(status = "timeout", error = "Request timed out (staged)"))
            "timeout-cached" -> AiUsageSnapshot(
                updatedAt = nowSec,
                grok = GrokQuota(status = "timeout", error = "Request timed out (staged)", lastOkAt = nowSec - 420L, lastOk = sampleOk),
            )
            else -> null
        }
    }

    override val stages get() = staging.stages
    override fun debug(args: List<String>) = staging.debug(args)

    private fun resolve(item: ItemConfig, nowMs: Long): ResolvedQuota<GrokQuota> {
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        return AiUsageReader.resolveGrok(nowMs, customPath, staging.of(item))
    }

    override fun state(item: ItemConfig): ItemState {
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        return buildQuotaState(
            icon = "brand:grok",
            widthKey = "ai_grok",
            providerTitle = "Grok",
            resolved = resolve(item, nowMs),
            threshold = threshold,
            extract = { gk ->
                val gkWRem = gk.cycle?.remaining?.toInt() ?: 100
                val usedPct = gk.cycle?.usedPercent ?: 0f
                QuotaBarValues(gkWRem, null, usedPct, "Weekly ${gkWRem}% remaining")
            },
            lastSummary = { gk -> "${gk.cycle?.remaining?.toInt() ?: 0}%" },
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        val resolved = resolve(item, nowMs)
        val tier = resolved.live?.tier ?: resolved.lastOk?.tier ?: "SuperGrok"

        MenuCard(
            "brand:grok",
            stringResource(R.string.item_ai_grok_title),
            subtitle = "xAI $tier · Weekly Quota"
        ) {
            val live = resolved.live
            if (live != null && live.cycle != null) {
                GrokCycleDetails(live.cycle, threshold, historical = false)
                LiveUpdatedNote(resolved.lastOkAtSec ?: resolved.snapshotUpdatedAtSec, nowMs)
            } else {
                QuotaProblemSection(resolved, nowMs) { cached ->
                    cached.cycle?.let { GrokCycleDetails(it, threshold, historical = true) }
                }
            }
            MenuDivider()
            MenuEntry(Sym.REFRESH, "立即重试获取") {
                AiUsageReader.forceRefresh()
            }
            MenuEntry(Sym.OPEN_IN_NEW, "打开 Grok") {
                host.close()
                launchAppOrUrl(
                    listOf("ai.x.grok"),
                    "https://grok.com"
                )
            }
            MenuEntry(Sym.SETTINGS, stringResource(R.string.context_item_settings)) {
                host.openItemSettings(item.id)
            }
        }
    }
}

/**
 * 4. Independent Grok Bot Item (Grok Bot Visor Robot icon + Single Weekly Remaining Ring with centered %)
 */
object GrokBotItem : ItemType("ai_grok_bot", R.string.item_ai_grok_bot_title, "brand:grok_bot", R.string.item_ai_grok_bot_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 320
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    private val sampleBuild = GrokCycle(usedPercent = 49f, remainingPercent = 51f, resetInSec = 194073L)

    private val staging = AiUsageStaging(type) { mode, nowSec ->
        when (mode) {
            "ok" -> AiUsageSnapshot(
                updatedAt = nowSec,
                grok = GrokQuota(
                    status = "ok",
                    cycle = GrokCycle(usedPercent = 19f, remainingPercent = 81f),
                    grokBuildStatus = "ok",
                    grokBuild = sampleBuild,
                    grokBuildOkAt = nowSec,
                ),
            )
            "timeout" -> AiUsageSnapshot(
                updatedAt = nowSec,
                grok = GrokQuota(
                    status = "ok",
                    cycle = GrokCycle(usedPercent = 19f, remainingPercent = 81f),
                    grokBuildStatus = "timeout",
                    grokBuildError = "WS timed out (staged)",
                ),
            )
            "timeout-cached" -> AiUsageSnapshot(
                updatedAt = nowSec,
                grok = GrokQuota(
                    status = "ok",
                    cycle = GrokCycle(usedPercent = 19f, remainingPercent = 81f),
                    grokBuildStatus = "timeout",
                    grokBuildError = "WS timed out (staged)",
                    grokBuildOkAt = nowSec - 420L,
                    lastOkGrokBuild = sampleBuild,
                ),
            )
            else -> null
        }
    }

    override val stages get() = staging.stages
    override fun debug(args: List<String>) = staging.debug(args)

    private fun resolve(item: ItemConfig, nowMs: Long): ResolvedQuota<GrokCycle> {
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        return AiUsageReader.resolveGrokBot(nowMs, customPath, staging.of(item))
    }

    override fun state(item: ItemConfig): ItemState {
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        return buildQuotaState(
            icon = "brand:grok_bot",
            widthKey = "ai_grok_bot",
            providerTitle = "Grok Bot",
            resolved = resolve(item, nowMs),
            threshold = threshold,
            extract = { b ->
                val rem = b.remaining.toInt()
                val usedPct = b.usedPercent ?: (100f - rem)
                QuotaBarValues(rem, null, usedPct, "${rem}% remaining")
            },
            lastSummary = { b -> "${b.remaining.toInt()}%" },
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val nowMs = Now.wall()
        val threshold = critThreshold.of(item).toFloat()
        val resolved = resolve(item, nowMs)

        MenuCard(
            "brand:grok_bot",
            stringResource(R.string.item_ai_grok_bot_title),
            subtitle = "Weekly Grok Bot Limit"
        ) {
            val live = resolved.live
            if (live != null) {
                GrokCycleDetails(live, threshold, historical = false)
                LiveUpdatedNote(resolved.lastOkAtSec ?: resolved.snapshotUpdatedAtSec, nowMs)
            } else {
                QuotaProblemSection(resolved, nowMs) { cached ->
                    GrokCycleDetails(cached, threshold, historical = true)
                }
            }
            MenuDivider()
            MenuEntry(Sym.REFRESH, "立即重试获取") {
                AiUsageReader.forceRefresh()
            }
            MenuEntry(Sym.OPEN_IN_NEW, "打开 Grok Bot") {
                host.close()
                launchAppOrUrl(
                    listOf("ai.x.grok"),
                    "https://grok.com/c"
                )
            }
            MenuEntry(Sym.SETTINGS, stringResource(R.string.context_item_settings)) {
                host.openItemSettings(item.id)
            }
        }
    }
}

/**
 * Legacy combined AI usage item kept for backwards-compatibility.
 */
object AiUsageItem : ItemType("ai_usage", R.string.item_ai_usage_title, Sym.BOLT, R.string.item_ai_usage_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 340
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    override fun state(item: ItemConfig): ItemState = AntigravityItem.state(item)
    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> AntigravityItem.menu(item, host) }
    override val optionsTitle: Int get() = R.string.item_ai_usage_title
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        TextRow(
            stringResource(R.string.ai_usage_options_path),
            item.opt("snapshotPath", ""),
            placeholder = "/storage/emulated/10/Codes/googleosbar/ai_usage_snapshot.json",
            help = stringResource(R.string.ai_usage_options_path_help),
            maxLength = 200
        ) {
            set(item.with("snapshotPath", it.trim().ifBlank { null }))
        }
    }
}

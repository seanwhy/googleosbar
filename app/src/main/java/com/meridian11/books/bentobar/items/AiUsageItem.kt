package com.meridian11.books.bentobar.items

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.SectionLabel
import com.meridian11.books.bentobar.ui.TextRow
import com.meridian11.books.bentobar.ui.rememberTick
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

/**
 * 1. Independent Antigravity Item (Official A-Arch icon + Concentric 5h/Weekly Dual-Ring with centered remaining %)
 */
object AntigravityItem : ItemType("ai_antigravity", R.string.item_ai_antigravity_title, "brand:antigravity", R.string.item_ai_antigravity_desc) {
    override val group = ItemGroup.TOOLS
    override val menuWidthDp = 320
    override val canBeActive = true
    private val critThreshold = Threshold("critPct", 80, 50..95, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_ai_usage, R.string.trigger_ai_usage_short, critThreshold)

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val ag = snapshot?.antigravity
        val g5hRem = ag?.gemini5h?.remaining?.toInt() ?: 85
        val gWRem = ag?.geminiWeekly?.remaining?.toInt() ?: 96
        val usedMax = maxOf(ag?.gemini5h?.usedPercent ?: 0f, ag?.geminiWeekly?.usedPercent ?: 0f)
        val threshold = critThreshold.of(item).toFloat()
        val isCrit = usedMax >= threshold
        return ItemState(
            icon = "brand:antigravity",
            text = "${gWRem}%",
            quotaRing = QuotaRing(outerPercent = gWRem, innerPercent = g5hRem),
            active = isCrit,
            tone = Tone.NORMAL,
            widthKey = "ai_antigravity",
            desc = "Antigravity: 5h ${g5hRem}% · Weekly ${gWRem}%"
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val threshold = critThreshold.of(item).toFloat()
        val ag = snapshot?.antigravity

        MenuCard(
            "brand:antigravity",
            stringResource(R.string.item_ai_antigravity_title),
            subtitle = "Gemini Models · 5h & Weekly Quotas"
        ) {
            if (ag == null || ag.status != "ok") {
                InfoRow(stringResource(R.string.ai_usage_status), ag?.error ?: ag?.reason ?: stringResource(R.string.ai_usage_waiting))
            } else {
                SectionLabel("Gemini Models (圆环主数据源)")
                ag.gemini5h?.let { b ->
                    val isCrit = (b.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "5-Hour Remaining (内环)",
                        "${b.remaining.toInt()}% · ${b.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(b.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                ag.geminiWeekly?.let { b ->
                    val isCrit = (b.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "Weekly Remaining (外环/圆心)",
                        "${b.remaining.toInt()}% · ${b.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(b.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                if (ag.claude5h != null || ag.claudeWeekly != null) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Claude & GPT Models")
                    ag.claude5h?.let { c5 ->
                        val isCrit = (c5.usedPercent ?: 0f) >= threshold
                        InfoRow(
                            "5-Hour Remaining",
                            "${c5.remaining.toInt()}% · ${c5.formattedCountdown()}",
                            if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Meter(c5.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                    }
                    ag.claudeWeekly?.let { cw ->
                        val isCrit = (cw.usedPercent ?: 0f) >= threshold
                        InfoRow(
                            "Weekly Remaining",
                            "${cw.remaining.toInt()}% · ${cw.formattedCountdown()}",
                            if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Meter(cw.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            MenuDivider()
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

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val cx = snapshot?.codex
        val cx5hRem = cx?.window5h?.remaining?.toInt() ?: 100
        val cxWRem = cx?.windowWeekly?.remaining?.toInt() ?: 30
        val usedMax = maxOf(cx?.window5h?.usedPercent ?: 0f, cx?.windowWeekly?.usedPercent ?: 0f)
        val threshold = critThreshold.of(item).toFloat()
        val isCrit = usedMax >= threshold
        return ItemState(
            icon = "brand:codex",
            text = "${cxWRem}%",
            quotaRing = QuotaRing(outerPercent = cxWRem, innerPercent = cx5hRem),
            active = isCrit,
            tone = Tone.NORMAL,
            widthKey = "ai_codex",
            desc = "Codex: 5h ${cx5hRem}% · Weekly ${cxWRem}%"
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val threshold = critThreshold.of(item).toFloat()
        val cx = snapshot?.codex
        val plan = cx?.planType?.uppercase() ?: "PLUS"

        MenuCard(
            "brand:codex",
            stringResource(R.string.item_ai_codex_title),
            subtitle = "OpenAI ChatGPT $plan · 5h & Weekly Quotas"
        ) {
            if (cx == null || cx.status != "ok") {
                InfoRow(stringResource(R.string.ai_usage_status), cx?.error ?: cx?.reason ?: stringResource(R.string.ai_usage_waiting))
            } else {
                cx.window5h?.let { w ->
                    val isCrit = (w.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "5-Hour Remaining (内环)",
                        "${w.remaining.toInt()}% · ${w.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(w.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                cx.windowWeekly?.let { w ->
                    val isCrit = (w.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "Weekly Remaining (外环/圆心)",
                        "${w.remaining.toInt()}% · ${w.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(w.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                }
            }
            MenuDivider()
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

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val gk = snapshot?.grok
        val gkWRem = gk?.cycle?.remaining?.toInt() ?: 53
        val usedPct = gk?.cycle?.usedPercent ?: 47f
        val threshold = critThreshold.of(item).toFloat()
        val isCrit = usedPct >= threshold
        return ItemState(
            icon = "brand:grok",
            text = "${gkWRem}%",
            quotaRing = QuotaRing(outerPercent = gkWRem, innerPercent = null),
            active = isCrit,
            tone = Tone.NORMAL,
            widthKey = "ai_grok",
            desc = "Grok: Weekly ${gkWRem}% remaining"
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val threshold = critThreshold.of(item).toFloat()
        val gk = snapshot?.grok ?: GrokQuota()

        MenuCard(
            "brand:grok",
            stringResource(R.string.item_ai_grok_title),
            subtitle = "xAI ${gk.tier} · Weekly Quota"
        ) {
            if (gk.status != "ok") {
                InfoRow(stringResource(R.string.ai_usage_status), gk.error ?: gk.status, MaterialTheme.colorScheme.error)
            } else {
                gk.cycle?.let { c ->
                    val isCrit = (c.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "Weekly Remaining",
                        "${c.remaining.toInt()}% (已用 ${c.usedPercent?.toInt() ?: 47}%) · ${c.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(c.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            MenuDivider()
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

    override fun state(item: ItemConfig): ItemState {
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val gk = snapshot?.grok
        val botCycle = gk?.grokBuild ?: gk?.cycle
        val botRem = botCycle?.remaining?.toInt() ?: 20
        val usedPct = botCycle?.usedPercent ?: 80f
        val threshold = critThreshold.of(item).toFloat()
        val isCrit = usedPct >= threshold
        return ItemState(
            icon = "brand:grok_bot",
            text = "${botRem}%",
            quotaRing = QuotaRing(outerPercent = botRem, innerPercent = null),
            active = isCrit,
            tone = Tone.NORMAL,
            widthKey = "ai_grok_bot",
            desc = "Grok Bot: ${botRem}% remaining"
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val now = Now.wall()
        val customPath = item.opt("snapshotPath", "").takeIf { it.isNotEmpty() }
        val snapshot = AiUsageReader.read(now, customPath)
        val threshold = critThreshold.of(item).toFloat()
        val gk = snapshot?.grok ?: GrokQuota()
        val botCycle = gk.grokBuild ?: gk.cycle

        MenuCard(
            "brand:grok_bot",
            stringResource(R.string.item_ai_grok_bot_title),
            subtitle = "Weekly Grok Bot Limit"
        ) {
            if (gk.status != "ok") {
                InfoRow(stringResource(R.string.ai_usage_status), gk.error ?: gk.status, MaterialTheme.colorScheme.error)
            } else {
                botCycle?.let { b ->
                    val isCrit = (b.usedPercent ?: 0f) >= threshold
                    InfoRow(
                        "Weekly Remaining",
                        "${b.remaining.toInt()}% (已用 ${b.usedPercent?.toInt() ?: 80}%) · ${b.formattedCountdown()}",
                        if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Meter(b.fraction, if (isCrit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            MenuDivider()
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

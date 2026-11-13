package com.meridian11.books.bentobar.items

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import com.meridian11.books.bentobar.data.Kept
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State and action helper for the system's Charging Optimization (80% charge limit).
 *
 * When `WRITE_SECURE_SETTINGS` is held, [tryFastToggle] flips the setting directly in 0 ms.
 * Otherwise [startUiToggle] opens Battery Settings via `BarService`, updates the tile highlight if
 * the Battery row summary is readable, and taps through to the Charging Optimization sub-page so
 * the user can toggle it in one tap.
 */
object ChargeOptimization {
    private const val PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    private const val KEY_MASTER_ENABLED = "charging_optimization_enabled"
    private const val KEY_ADAPTIVE = "adaptive_charging_enabled"
    private const val KEY_MODE = "charge_optimization_mode"

    /** Last confirmed state (`true` = 80% limit enabled, `false` = off, `null` = unknown). */
    val on = MutableStateFlow<Boolean?>(null)

    fun init(context: Context) {
        runCatching {
            when (Kept.own("charge_opt").read("on")?.text?.trim()) {
                "1" -> on.value = true
                "0" -> on.value = false
            }
        }
        refreshFromSystem(context)
    }

    /**
     * Reads the live system state only when a true master-enable key is readable.
     * `charge_optimization_mode` alone holds the selected option (1 = 80% limit) even while
     * disabled, so it must not be treated as the master state by itself.
     */
    fun readSystemSetting(context: Context): Boolean? {
        val cr = context.contentResolver
        runCatching {
            val enabled = Settings.Secure.getInt(cr, KEY_MASTER_ENABLED, -1)
            if (enabled >= 0) {
                if (enabled == 0) return false
                val mode = runCatching { Settings.Secure.getInt(cr, KEY_MODE, 1) }.getOrDefault(1)
                return mode == 1
            }
        }
        return null
    }

    fun refreshFromSystem(context: Context): Boolean? {
        readSystemSetting(context)?.let { live ->
            if (on.value != live) on.value = live
            return live
        }
        return on.value
    }

    fun tryWriteSystemSetting(context: Context, targetOn: Boolean): Boolean {
        if (context.checkSelfPermission(PERM_WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val cr = context.contentResolver
        val flag = if (targetOn) 1 else 0
        return try {
            val wroteMode = Settings.Secure.putInt(cr, KEY_MODE, flag)
            runCatching { Settings.Secure.putInt(cr, KEY_MASTER_ENABLED, flag) }
            if (targetOn) {
                runCatching { Settings.Secure.putInt(cr, KEY_ADAPTIVE, 0) }
            }
            if (wroteMode) recordConfirmed(targetOn)
            wroteMode
        } catch (_: Throwable) {
            false
        }
    }

    fun recordConfirmed(enabled: Boolean) {
        on.value = enabled
        runCatching {
            Background.executor("charge-opt").execute {
                runCatching {
                    Kept.own("charge_opt").write("on", if (enabled) "1" else "0", System.currentTimeMillis())
                }
            }
        }
    }

    fun buildBatteryIntent(): Intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )
        putExtra(":settings:fragment_args_key", "charge_optimization")
        putExtra(":settings:show_fragment_args", Bundle().apply {
            putString(":settings:fragment_args_key", "charge_optimization")
        })
    }

    /** Fast 0 ms path when `WRITE_SECURE_SETTINGS` is granted; returns false on plain installs. */
    fun tryFastToggle(): Boolean {
        val app = runCatching { Env.app }.getOrNull() ?: return false
        if (app.checkSelfPermission(PERM_WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return false
        val target = !(refreshFromSystem(app) ?: true)
        return tryWriteSystemSetting(app, target)
    }

    /** Runs after the Tools popup has dismissed so window inspection sees all Settings panes. */
    fun startUiToggle() {
        val app = runCatching { Env.app }.getOrNull() ?: return
        refreshFromSystem(app)
        if (Env.toggleChargeOptimization()) return

        if (!Env.launch(buildBatteryIntent(), quiet = true)) {
            Env.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }
}

/**
 * Pure, JVM unit-tested navigation rules for reaching the Settings Charging Optimization sub-page
 * from Battery Settings (handling single-pane and two-pane desktop layouts, plus scroll fallback).
 */
object ChargeOptimizationRules {
    /** Minimum settle wait after Settings appears before tapping a navigation row. */
    const val PRE_NAV_SETTLE_MS = 300L

    /** Cooldown before retrying navigation on the same row (e.g. `ACTION_CLICK` fallback). */
    const val RETRY_NAV_MS = 550L

    /** Cooldown between consecutive forward scrolls while hunting for the Charging Optimization row. */
    const val SCROLL_RETRY_MS = 250L

    data class UiNode(
        val ref: Int,
        val id: String = "",
        val cls: String = "",
        val text: String = "",
        val desc: String = "",
        val stateDesc: String = "",
        val paneTitle: String = "",
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val checkedState: Int = -1, // API 36+: 1 = CHECKED_STATE_TRUE, 0 = CHECKED_STATE_FALSE
        val selected: Boolean = false,
        val clickable: Boolean = false,
        val scrollable: Boolean = false,
        val enabled: Boolean = true,
        val visible: Boolean = true,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val children: List<UiNode> = emptyList(),
    )

    data class SettingScreen(
        val pkg: String,
        val windowId: Int,
        val tree: UiNode,
        val isNewWindow: Boolean = false,
        val windowLeft: Int = tree.left,
        val windowTop: Int = tree.top,
        val windowRight: Int = tree.right,
        val windowBottom: Int = tree.bottom,
        val displayId: Int = 0,
    )

    sealed interface Step {
        data object Arrived : Step
        data class ClickNav(val ref: Int, val navKey: String) : Step
        data class Scroll(val ref: Int) : Step
        data object Wait : Step
    }

    private val CHARGE_OPT_ROW_LABELS = setOf(
        "充电优化",
        "充电保护",
        "电池充电优化",
        "充电上限",
        "限制充电至 80%",
        "充電最佳化",
        "充電保護",
        "charging optimization",
        "charge optimization",
        "charging optimisation",
        "charge optimisation",
        "charge limit",
        "battery protection",
    )

    private val SETTINGS_HOMEPAGE_ROW_LABELS = setOf(
        "网络和互联网",
        "已连接的设备",
        "应用",
        "通知",
        "存储",
        "声音和振动",
        "显示",
        "壁纸和样式",
        "无障碍",
        "安全和隐私",
        "位置信息",
        "密码和通行密钥",
        "账号和备份",
        "数字健康",
        "家长控制",
        "系统",
        "关于设备",
        "关于手机",
        "关于平板电脑",
        "network & internet",
        "connected devices",
        "apps",
        "notifications",
        "storage",
        "sound & vibration",
        "display",
        "wallpaper & style",
        "accessibility",
        "security & privacy",
        "location",
        "passwords & passkeys",
        "accounts & backup",
        "digital wellbeing",
        "system",
        "about device",
        "about phone",
        "about tablet",
    )

    private val BATTERY_OVERVIEW_ROW_LABELS = setOf(
        "省电模式",
        "超级省电",
        "电池用量",
        "电池使用情况",
        "应用电池用量",
        "电池管理器",
        "自适应电池",
        "电池电量百分比",
        "电池信息",
        "省電模式",
        "電池用量",
        "電池使用情況",
        "電池資訊",
        "battery saver",
        "extreme battery saver",
        "battery usage",
        "battery manager",
        "adaptive battery",
        "battery percentage",
        "battery information",
    )

    private val BATTERY_HEALTH_LABELS = setOf(
        "电池健康",
        "电池健康状况",
        "电池诊断",
        "電池健康度",
        "電池健康狀況",
        "battery health",
        "battery diagnostics",
    )

    private val HELP_LINK_HINTS = listOf(
        "详细了解",
        "了解详情",
        "了解更多",
        "进一步了解",
        "瞭解詳情",
        "深入瞭解",
        "learn more",
        "more about",
    )

    private val SUBPAGE_HINTS = listOf(
        "充电优化",
        "充电保护",
        "充电上限",
        "限制为 80%",
        "限制充电至 80%",
        "充電最佳化",
        "充電上限",
        "自适应充电",
        "自動調節充電",
        "charging optimization",
        "charge optimization",
        "charging optimisation",
        "adaptive charging",
        "charge limit",
        "limit to 80%",
        "limit charging to 80%",
        "battery protection",
    )

    private val OFF_OPTION_HINTS = listOf(
        "关闭",
        "不限制",
        "不使用",
        "不开启",
        "不启用",
        "无优化",
        "充满",
        "完全充电",
        "标准充电",
        "100%",
        "關閉",
        "off",
        "none",
        "no limit",
        "full charge",
        "standard",
    )

    private val EXACT_OFF_LABELS = setOf("无", "关", "關")

    private val STATE_ON_WORDS = setOf(
        "开启", "已开启", "已启用", "已打开", "开", "打开",
        "已选中", "已选择", "选中", "已勾选",
        "開啟", "已開啟", "開", "已選取", "揀咗",
        "on", "checked", "selected", "enabled",
    )

    private val STATE_OFF_WORDS = setOf(
        "关闭", "已关闭", "已停用", "已禁用", "关",
        "未开启", "未启用", "未打开", "未选中", "未选择", "未勾选",
        "關閉", "已關閉", "未選取", "未揀", "閂",
        "off", "not checked", "unchecked", "not selected", "disabled",
    )

    private val WHITESPACE_RE = Regex("\\s+")

    fun isSettingsPackage(pkg: String, ownPackage: String): Boolean {
        if (pkg.isEmpty() || pkg == ownPackage || pkg == "com.android.systemui") return false
        return pkg == "com.android.settings" ||
            pkg.startsWith("com.android.settings.") ||
            pkg.startsWith("com.google.android.settings") ||
            pkg == "com.google.android.apps.turbo" ||
            pkg.contains("fuelgauge") ||
            pkg.endsWith(".settings")
    }

    fun isCandidateWindow(pkg: String, ownPackage: String, isNewWindow: Boolean): Boolean {
        if (pkg.isEmpty() || pkg == ownPackage || pkg == "com.android.systemui") return false
        if (isSettingsPackage(pkg, ownPackage)) return true
        if (!isNewWindow) return false
        val low = pkg.lowercase()
        return low == "android" ||
            ".settings" in low ||
            "settings." in low ||
            "devicehealth" in low ||
            "turbo" in low ||
            "fuelgauge" in low
    }

    internal fun norm(s: String): String =
        s.replace('％', '%')
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("「", "")
            .replace("」", "")
            .replace("『", "")
            .replace("』", "")
            .trim()
            .lowercase()
            .replace(WHITESPACE_RE, " ")

    private fun isPureStateWord(line: String): Boolean =
        line in STATE_ON_WORDS || line in STATE_OFF_WORDS

    private fun isOffLabel(label: String): Boolean =
        label in EXACT_OFF_LABELS || OFF_OPTION_HINTS.any { it in label }

    private fun isCheckWidget(n: UiNode): Boolean {
        if (!n.visible) return false
        val id = n.id.lowercase()
        val cls = n.cls
        val sd = norm(n.stateDesc)
        val radioOrSwitch = cls.endsWith("RadioButton") ||
            cls.endsWith("CheckedTextView") ||
            id.endsWith("/radio") ||
            id.endsWith(":id/radio") ||
            "select_dialog" in id ||
            cls.endsWith("Switch") ||
            cls.endsWith("SwitchCompat") ||
            cls.endsWith("MaterialSwitch") ||
            cls.endsWith("SwitchMaterial") ||
            cls.endsWith("CompoundButton") ||
            cls.endsWith("ToggleButton") ||
            cls.endsWith("CheckBox") ||
            id.endsWith("/switch_widget") ||
            id.endsWith(":id/switch_widget")
        return n.checkable || n.checked || n.checkedState == 1 || radioOrSwitch ||
            sd in STATE_ON_WORDS || sd in STATE_OFF_WORDS
    }

    private fun subtreeHasToggle(n: UiNode): Boolean =
        isCheckWidget(n) || n.children.any { it.visible && subtreeHasToggle(it) }

    private fun collectRawLines(n: UiNode, out: MutableList<String>) {
        if (!n.visible) return
        if (n.text.isNotBlank()) {
            n.text.lineSequence().map(::norm).filter { it.isNotEmpty() }.forEach { line ->
                if (line !in out) out += line
            }
        }
        if (n.desc.isNotBlank()) {
            n.desc.lineSequence().map(::norm).filter { it.isNotEmpty() }.forEach { line ->
                if (line !in out) out += line
            }
        }
        for (c in n.children) collectRawLines(c, out)
    }

    private fun hasNonWidgetClickableDescendant(n: UiNode): Boolean {
        for (c in n.children) {
            if (!c.visible) continue
            if (c.clickable && c.enabled && !isCheckWidget(c)) return true
            if (hasNonWidgetClickableDescendant(c)) return true
        }
        return false
    }

    private data class RowCandidate(
        val clickRef: Int?,
        val primaryLabel: String,
        val allLines: List<String>,
        val hasToggle: Boolean,
        val left: Int,
        val right: Int,
    )

    private fun extractRows(tree: UiNode): List<RowCandidate> {
        val rows = ArrayList<RowCandidate>()
        fun walk(n: UiNode) {
            if (!n.visible) return
            val selfClickable = n.clickable && n.enabled

            // Two-target preference layout: non-clickable row with clickable text box and sibling switch
            if (!selfClickable && !n.scrollable && n.children.size in 2..3) {
                val switchChild = n.children.firstOrNull { child ->
                    child.visible &&
                        ArrayList<String>().also { collectRawLines(child, it) }.none { !isPureStateWord(it) } &&
                        subtreeHasToggle(child)
                }
                val labelChild = n.children.firstOrNull { child ->
                    child.visible &&
                        child !== switchChild &&
                        child.clickable &&
                        child.enabled &&
                        !hasNonWidgetClickableDescendant(child) &&
                        !subtreeHasToggle(child)
                }
                val othersInert = n.children.all { child ->
                    !child.visible || child === switchChild || child === labelChild ||
                        (!child.clickable && !subtreeHasToggle(child))
                }
                if (switchChild != null && labelChild != null && othersInert) {
                    val lines = ArrayList<String>().also { collectRawLines(labelChild, it) }
                    if (lines.isNotEmpty()) {
                        val l = if (n.right > n.left) n.left else labelChild.left
                        val r = if (n.right > n.left) n.right else maxOf(labelChild.right, switchChild.right)
                        rows += RowCandidate(
                            clickRef = labelChild.ref,
                            primaryLabel = lines.first(),
                            allLines = lines,
                            hasToggle = true,
                            left = l,
                            right = r,
                        )
                        return
                    }
                }
            }

            if (selfClickable && !hasNonWidgetClickableDescendant(n)) {
                val rawLines = ArrayList<String>().also { collectRawLines(n, it) }
                val hasToggle = subtreeHasToggle(n)
                val lines = if (hasToggle) {
                    rawLines.filterNot(::isPureStateWord).ifEmpty { rawLines }
                } else {
                    rawLines
                }
                if (lines.isNotEmpty() || hasToggle) {
                    rows += RowCandidate(
                        clickRef = n.ref,
                        primaryLabel = lines.firstOrNull().orEmpty(),
                        allLines = lines,
                        hasToggle = hasToggle,
                        left = n.left,
                        right = n.right,
                    )
                }
                return
            }

            for (c in n.children) walk(c)
        }
        walk(tree)
        return rows
    }

    private data class PaneSlice(
        val screen: SettingScreen,
        val rows: List<RowCandidate>,
        val fullText: String,
        val isRightDetailPane: Boolean,
        val minX: Int = Int.MIN_VALUE,
        val maxX: Int = Int.MAX_VALUE,
    )

    private fun isNodeInBand(n: UiNode, minLeft: Int, maxRight: Int): Boolean {
        val hasBox = n.right > n.left
        return !hasBox ||
            (n.left >= minLeft && n.right <= maxRight) ||
            (n.left >= minLeft && maxRight == Int.MAX_VALUE)
    }

    private fun collectTextInRange(n: UiNode, minLeft: Int, maxRight: Int, out: StringBuilder) {
        if (!n.visible) return
        if (isNodeInBand(n, minLeft, maxRight)) {
            if (n.paneTitle.isNotBlank()) out.append(norm(n.paneTitle)).append('\n')
            if (n.text.isNotBlank()) out.append(norm(n.text)).append('\n')
            if (n.desc.isNotBlank()) out.append(norm(n.desc)).append('\n')
        }
        for (c in n.children) collectTextInRange(c, minLeft, maxRight, out)
    }

    /**
     * Splits a wide two-pane Settings window into `[RightDetailPane, LeftNavPane]` whenever
     * rows occupy distinct left and right columns.
     */
    private fun splitIntoPanes(s: SettingScreen): List<PaneSlice> {
        val rows = extractRows(s.tree)
        val winL = if (s.windowRight > s.windowLeft) s.windowLeft else s.tree.left
        val winR = if (s.windowRight > s.windowLeft) s.windowRight else s.tree.right
        val winW = winR - winL

        if (winW >= 520) {
            val leftStartMax = winL + (winW * 28) / 100
            val leftEndMax = winL + (winW * 58) / 100
            val baseRightStartMin = winL + (winW * 25) / 100

            fun isLeftBox(l: Int, r: Int) = r > l && l < leftStartMax && r <= leftEndMax

            val maxLeftCardRight = rows.filter { isLeftBox(it.left, it.right) }.maxOfOrNull { it.right }
            val rightStartMin = if (maxLeftCardRight != null) {
                maxOf(baseRightStartMin, maxLeftCardRight - 8)
            } else {
                baseRightStartMin
            }

            fun isRightBox(l: Int, r: Int) = r > l && l >= rightStartMin

            if (rows.any { isLeftBox(it.left, it.right) } && rows.any { isRightBox(it.left, it.right) }) {
                val rightRows = rows.filter { isRightBox(it.left, it.right) }
                val leftRows = rows.filterNot { isRightBox(it.left, it.right) }
                val splitX = rightRows.minOf { it.left }
                val rightText = StringBuilder().also { collectTextInRange(s.tree, splitX - 40, Int.MAX_VALUE, it) }.toString()
                val leftText = StringBuilder().also { collectTextInRange(s.tree, Int.MIN_VALUE, splitX + 80, it) }.toString()

                return listOf(
                    PaneSlice(s, rightRows, rightText, isRightDetailPane = true, minX = splitX - 16, maxX = Int.MAX_VALUE),
                    PaneSlice(s, leftRows, leftText, isRightDetailPane = false, minX = Int.MIN_VALUE, maxX = splitX + 16),
                )
            }
        }

        val fullText = StringBuilder().also { collectTextInRange(s.tree, Int.MIN_VALUE, Int.MAX_VALUE, it) }.toString()
        return listOf(PaneSlice(s, rows, fullText, isRightDetailPane = false))
    }

    internal fun rowSummaryState(allLines: List<String>): Boolean? {
        for (raw in allLines.drop(1)) {
            val line = norm(raw)
            if (line.isEmpty()) continue
            if (line in STATE_OFF_WORDS || isOffLabel(line)) return false
            if ("80%" in line ||
                line in STATE_ON_WORDS ||
                "已开启" in line ||
                "已启用" in line ||
                "已打开" in line ||
                "充电上限" in line ||
                "充電上限" in line ||
                ("limit" in line && "no limit" !in line) ||
                "adaptive" in line ||
                "自适应" in line ||
                "自動調節" in line
            ) {
                return true
            }
        }
        return null
    }

    /** Returns the Charging Optimization row's summary state on Battery Overview if readable, else null. */
    fun observeBatteryRowState(screens: List<SettingScreen>): Boolean? {
        val panes = screens.flatMap(::splitIntoPanes).sortedWith(
            compareByDescending<PaneSlice> { it.isRightDetailPane }
                .thenByDescending { it.screen.isNewWindow },
        )
        for (pane in panes) {
            val optRow = pane.rows.firstOrNull { !it.hasToggle && it.primaryLabel in CHARGE_OPT_ROW_LABELS } ?: continue
            rowSummaryState(optRow.allLines)?.let { return it }
        }
        return null
    }

    private fun hasChargingSubpageId(n: UiNode, minX: Int, maxX: Int): Boolean {
        if (isNodeInBand(n, minX, maxX)) {
            val id = n.id.lowercase()
            if ("charging_optimization" in id || "charge_optimization" in id) return true
        }
        return n.children.any { hasChargingSubpageId(it, minX, maxX) }
    }

    private fun hasChargingFooterMarker(n: UiNode, minX: Int, maxX: Int): Boolean {
        if (!n.visible) return false
        if (isNodeInBand(n, minX, maxX)) {
            val id = n.id.lowercase()
            if (id.endsWith("settingslib_learn_more")) return true
            val txt = norm(listOf(n.text, n.desc).filter { it.isNotBlank() }.joinToString(" "))
            if (txt.isNotEmpty() && (
                    "延长电池寿命" in txt ||
                        "延長電池壽命" in txt ||
                        "battery lifespan" in txt ||
                        "电池容量数据" in txt ||
                        "電池容量資料" in txt ||
                        HELP_LINK_HINTS.any { it in txt }
                    )
            ) {
                return true
            }
        }
        return n.children.any { hasChargingFooterMarker(it, minX, maxX) }
    }

    private fun isSubpagePane(pane: PaneSlice): Boolean {
        val rows = pane.rows
        val isHomepageNavPane = rows.count { it.primaryLabel in SETTINGS_HOMEPAGE_ROW_LABELS } >= 2
        if (isHomepageNavPane) return false

        val isBatteryOverviewPane = rows.any { it.primaryLabel in BATTERY_OVERVIEW_ROW_LABELS }
        if (isBatteryOverviewPane) return false

        val hasNavRowToSubpage = rows.any { !it.hasToggle && it.primaryLabel in CHARGE_OPT_ROW_LABELS }
        if (hasNavRowToSubpage) return false

        return hasChargingSubpageId(pane.screen.tree, pane.minX, pane.maxX) ||
            hasChargingFooterMarker(pane.screen.tree, pane.minX, pane.maxX) ||
            SUBPAGE_HINTS.any { it in pane.fullText }
    }

    /** True if any visible Settings pane currently displays the Charging Optimization sub-page. */
    fun isSubpageVisible(screens: List<SettingScreen>): Boolean =
        screens.flatMap(::splitIntoPanes).any(::isSubpagePane)

    private fun findRightmostScrollable(screens: List<SettingScreen>): UiNode? {
        var best: UiNode? = null
        fun walk(n: UiNode) {
            if (!n.visible) return
            if (n.scrollable && n.enabled) {
                if (best == null || n.left > best!!.left ||
                    (n.left == best!!.left && (n.bottom - n.top) > (best!!.bottom - best!!.top))
                ) {
                    best = n
                }
            }
            for (c in n.children) walk(c)
        }
        for (s in screens) walk(s.tree)
        return best
    }

    fun nextStep(
        screens: List<SettingScreen>,
        lastNavKey: String? = null,
        navAttempts: Int = 0,
        scrollCount: Int = 0,
        triedBatteryHealth: Boolean = false,
        sinceNavMs: Long = Long.MAX_VALUE,
    ): Step {
        val panes = screens.flatMap(::splitIntoPanes).sortedWith(
            compareByDescending<PaneSlice> { it.isRightDetailPane }
                .thenByDescending { it.screen.isNewWindow },
        )
        if (panes.any(::isSubpagePane)) return Step.Arrived

        val chargeOptRow = panes.firstNotNullOfOrNull { pane ->
            pane.rows.firstOrNull { !it.hasToggle && it.primaryLabel in CHARGE_OPT_ROW_LABELS && it.clickRef != null }
        }
        if (chargeOptRow != null) {
            if (lastNavKey != "charge_opt_row" || (navAttempts < 2 && sinceNavMs >= RETRY_NAV_MS)) {
                return Step.ClickNav(chargeOptRow.clickRef!!, "charge_opt_row")
            }
            return Step.Wait
        }

        if (panes.any { it.rows.isNotEmpty() } && scrollCount < 2 &&
            (lastNavKey != "scroll" || sinceNavMs >= SCROLL_RETRY_MS)
        ) {
            findRightmostScrollable(screens)?.let { return Step.Scroll(it.ref) }
        }

        if (!triedBatteryHealth) {
            val healthRow = panes.firstNotNullOfOrNull { pane ->
                pane.rows.firstOrNull { !it.hasToggle && it.primaryLabel in BATTERY_HEALTH_LABELS && it.clickRef != null }
            }
            if (healthRow != null &&
                (lastNavKey != "battery_health" || (navAttempts < 2 && sinceNavMs >= RETRY_NAV_MS))
            ) {
                return Step.ClickNav(healthRow.clickRef!!, "battery_health")
            }
        }

        return Step.Wait
    }
}

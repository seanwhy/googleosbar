package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.ChargeOptimizationRules.SettingScreen
import com.meridian11.books.bentobar.items.ChargeOptimizationRules.Step
import com.meridian11.books.bentobar.items.ChargeOptimizationRules.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeOptimizationTest {

    @Test
    fun `isCandidateWindow filters non-settings windows even when new`() {
        assertTrue(ChargeOptimizationRules.isCandidateWindow("com.android.settings", "own", false))
        assertTrue(ChargeOptimizationRules.isCandidateWindow("com.google.android.settings.intelligence", "own", false))
        assertTrue(ChargeOptimizationRules.isCandidateWindow("com.google.android.apps.turbo", "own", false))
        assertTrue(ChargeOptimizationRules.isCandidateWindow("android", "own", true))
        assertFalse(ChargeOptimizationRules.isCandidateWindow("android", "own", false))
        assertFalse(ChargeOptimizationRules.isCandidateWindow("own", "own", true))
        assertFalse(ChargeOptimizationRules.isCandidateWindow("com.android.systemui", "own", true))
        assertFalse(ChargeOptimizationRules.isCandidateWindow("com.google.android.apps.antigravity", "own", true))
        assertFalse(ChargeOptimizationRules.isCandidateWindow("com.android.chrome", "own", true))
    }

    @Test
    fun `observeBatteryRowState reads localized summary on battery overview`() {
        assertEquals(true, ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen("充电上限为 80%"))))
        assertEquals(true, ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen("Limit to 80%"))))
        assertEquals(true, ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen("自适应充电"))))
        assertEquals(false, ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen("已关闭"))))
        assertEquals(false, ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen("Off"))))
        assertNull(ChargeOptimizationRules.observeBatteryRowState(listOf(batteryOverviewScreen(""))))
    }

    @Test
    fun `navigates from two-pane battery overview to charging optimization subpage`() {
        val navStep = ChargeOptimizationRules.nextStep(
            screens = listOf(leftHomepageWindow(), batteryOverviewScreen("充电上限为 80%")),
        )
        assertEquals(Step.ClickNav(ref = 204, navKey = "charge_opt_row"), navStep)
    }

    @Test
    fun `arrives immediately on desktop hidden-switch subpage and pre-inflated subpage`() {
        val preInflated = SettingScreen(
            pkg = "com.google.android.settings.intelligence",
            windowId = 672,
            isNewWindow = true,
            windowLeft = 472,
            windowTop = 59,
            windowRight = 1432,
            windowBottom = 1709,
            tree = UiNode(
                ref = 0,
                cls = "android.widget.FrameLayout",
                left = 472,
                top = 59,
                right = 1432,
                bottom = 1709,
                children = listOf(
                    UiNode(ref = 5, desc = "充电优化", left = 472, top = 124, right = 1432, bottom = 215),
                    UiNode(ref = 11, id = "com.google.android.settings.intelligence:id/fragment_charging_optimization_content", left = 472, top = 215, right = 1432, bottom = 1232),
                ),
            ),
        )
        assertTrue(ChargeOptimizationRules.isSubpageVisible(listOf(leftHomepageWindow(), preInflated)))
        assertEquals(Step.Arrived, ChargeOptimizationRules.nextStep(listOf(leftHomepageWindow(), preInflated)))

        val settledHidden = listOf(leftHomepageWindow(), hiddenSwitchSubpageScreen())
        assertTrue(ChargeOptimizationRules.isSubpageVisible(settledHidden))
        assertEquals(Step.Arrived, ChargeOptimizationRules.nextStep(settledHidden))
    }

    @Test
    fun `single-window two-pane homepage plus charging subpage detects arrival`() {
        val combined = SettingScreen(
            pkg = "com.android.settings",
            windowId = 50,
            windowLeft = 0,
            windowTop = 0,
            windowRight = 1400,
            windowBottom = 900,
            tree = UiNode(
                ref = 0,
                left = 0,
                top = 0,
                right = 1400,
                bottom = 900,
                children = listOf(
                    UiNode(
                        ref = 1,
                        clickable = true,
                        left = 20,
                        top = 100,
                        right = 420,
                        bottom = 180,
                        children = listOf(UiNode(ref = 2, text = "Network & internet", left = 40, top = 120, right = 380, bottom = 160)),
                    ),
                    UiNode(
                        ref = 3,
                        clickable = true,
                        left = 20,
                        top = 190,
                        right = 420,
                        bottom = 270,
                        children = listOf(UiNode(ref = 4, text = "Apps", left = 40, top = 210, right = 380, bottom = 250)),
                    ),
                    UiNode(
                        ref = 10,
                        clickable = true,
                        left = 500,
                        top = 120,
                        right = 1360,
                        bottom = 220,
                        children = listOf(
                            UiNode(ref = 11, text = "Use charging optimization", left = 520, top = 140, right = 1100, bottom = 200),
                            UiNode(ref = 12, cls = "android.widget.Switch", checkable = true, checked = true, left = 1240, top = 140, right = 1340, bottom = 200),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(ChargeOptimizationRules.isSubpageVisible(listOf(combined)))
        assertEquals(Step.Arrived, ChargeOptimizationRules.nextStep(listOf(combined)))
    }

    @Test
    fun `scrolls rightmost container to reveal offscreen charge optimization row`() {
        val unpopulated = SettingScreen(
            pkg = "com.android.settings",
            windowId = 849,
            windowLeft = 760,
            windowTop = 59,
            windowRight = 2880,
            windowBottom = 1709,
            tree = UiNode(
                ref = 0,
                id = "recyclerview",
                scrollable = true,
                left = 760,
                top = 228,
                right = 2880,
                bottom = 1709,
            ),
        )
        assertEquals(Step.Wait, ChargeOptimizationRules.nextStep(listOf(unpopulated)))

        val topOnlyRows = SettingScreen(
            pkg = "com.android.settings",
            windowId = 849,
            windowLeft = 760,
            windowTop = 59,
            windowRight = 2880,
            windowBottom = 1709,
            tree = UiNode(
                ref = 0,
                id = "recycler_view",
                scrollable = true,
                left = 760,
                top = 228,
                right = 2880,
                bottom = 1709,
                children = listOf(
                    clickableRow(ref = 10, title = "省电模式", subtitle = "关闭", left = 760, top = 400, right = 2880, bottom = 520),
                    clickableRow(ref = 11, title = "电池健康", subtitle = "正常", left = 760, top = 540, right = 2880, bottom = 660),
                ),
            ),
        )

        val scroll1 = ChargeOptimizationRules.nextStep(listOf(leftHomepageWindow(), topOnlyRows), scrollCount = 0)
        assertEquals(Step.Scroll(0), scroll1)

        val scroll2 = ChargeOptimizationRules.nextStep(
            listOf(leftHomepageWindow(), topOnlyRows),
            lastNavKey = "scroll",
            scrollCount = 1,
            sinceNavMs = ChargeOptimizationRules.SCROLL_RETRY_MS,
        )
        assertEquals(Step.Scroll(0), scroll2)

        val healthFallback = ChargeOptimizationRules.nextStep(
            listOf(leftHomepageWindow(), topOnlyRows),
            lastNavKey = "scroll",
            scrollCount = 2,
            triedBatteryHealth = false,
            sinceNavMs = ChargeOptimizationRules.SCROLL_RETRY_MS,
        )
        assertEquals(Step.ClickNav(11, "battery_health"), healthFallback)

        val noLoopAfterHealth = ChargeOptimizationRules.nextStep(
            listOf(leftHomepageWindow(), topOnlyRows),
            scrollCount = 2,
            triedBatteryHealth = true,
        )
        assertEquals(Step.Wait, noLoopAfterHealth)
    }

    @Test
    fun `navigates through battery health when it holds a non-toggle charge opt row`() {
        val batteryHealthScreen = SettingScreen(
            pkg = "com.android.settings",
            windowId = 850,
            isNewWindow = true,
            windowLeft = 760,
            windowTop = 59,
            windowRight = 2880,
            windowBottom = 1709,
            tree = UiNode(
                ref = 0,
                left = 760,
                top = 59,
                right = 2880,
                bottom = 1709,
                children = listOf(
                    UiNode(ref = 1, desc = "电池健康", left = 760, top = 124, right = 2880, bottom = 228),
                    clickableRow(ref = 2, title = "充电优化", subtitle = "已关闭", left = 760, top = 300, right = 2880, bottom = 420),
                ),
            ),
        )
        assertFalse(ChargeOptimizationRules.isSubpageVisible(listOf(batteryHealthScreen)))
        assertEquals(
            Step.ClickNav(ref = 2, navKey = "charge_opt_row"),
            ChargeOptimizationRules.nextStep(listOf(batteryHealthScreen), lastNavKey = "battery_health", triedBatteryHealth = true),
        )
    }

    @Test
    fun `retries charge opt nav at most twice with cooldown`() {
        val overview = listOf(batteryOverviewScreen("已关闭"))
        assertEquals(
            Step.Wait,
            ChargeOptimizationRules.nextStep(
                screens = overview,
                lastNavKey = "charge_opt_row",
                navAttempts = 1,
                sinceNavMs = 150L,
            ),
        )
        assertEquals(
            Step.ClickNav(204, "charge_opt_row"),
            ChargeOptimizationRules.nextStep(
                screens = overview,
                lastNavKey = "charge_opt_row",
                navAttempts = 1,
                sinceNavMs = ChargeOptimizationRules.RETRY_NAV_MS,
            ),
        )
        assertEquals(
            Step.Wait,
            ChargeOptimizationRules.nextStep(
                screens = overview,
                lastNavKey = "charge_opt_row",
                navAttempts = 2,
                sinceNavMs = 1_000L,
            ),
        )
    }

    private fun clickableRow(
        ref: Int,
        title: String,
        subtitle: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): UiNode = UiNode(
        ref = ref,
        clickable = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        children = listOf(
            UiNode(ref = ref * 10 + 1, text = title, left = left + 20, top = top + 10, right = right - 20, bottom = top + 50),
            UiNode(ref = ref * 10 + 2, text = subtitle, left = left + 20, top = top + 55, right = right - 20, bottom = bottom - 10),
        ),
    )

    private fun leftHomepageWindow(): SettingScreen = SettingScreen(
        pkg = "com.android.settings",
        windowId = 696,
        windowLeft = 0,
        windowTop = 59,
        windowRight = 760,
        windowBottom = 1709,
        tree = UiNode(
            ref = 100,
            cls = "android.widget.FrameLayout",
            left = 0,
            top = 59,
            right = 760,
            bottom = 1709,
            children = listOf(
                clickableRow(ref = 101, title = "网络和互联网", subtitle = "WLAN", left = 20, top = 200, right = 740, bottom = 310),
                clickableRow(ref = 102, title = "已连接的设备", subtitle = "蓝牙", left = 20, top = 320, right = 740, bottom = 430),
                clickableRow(ref = 103, title = "应用", subtitle = "默认应用", left = 20, top = 440, right = 740, bottom = 550),
            ),
        ),
    )

    private fun batteryOverviewScreen(summary: String): SettingScreen = SettingScreen(
        pkg = "com.android.settings",
        windowId = 849,
        isNewWindow = true,
        windowLeft = 760,
        windowTop = 59,
        windowRight = 2880,
        windowBottom = 1709,
        tree = UiNode(
            ref = 200,
            cls = "android.widget.FrameLayout",
            left = 760,
            top = 59,
            right = 2880,
            bottom = 1709,
            children = listOf(
                UiNode(ref = 201, desc = "电池", left = 760, top = 124, right = 2880, bottom = 228),
                clickableRow(ref = 202, title = "电池用量", subtitle = "查看应用耗电情况", left = 760, top = 400, right = 2880, bottom = 560),
                clickableRow(ref = 203, title = "省电模式", subtitle = "已关闭", left = 760, top = 580, right = 2880, bottom = 740),
                clickableRow(ref = 204, title = "充电优化", subtitle = summary, left = 760, top = 760, right = 2880, bottom = 920),
            ),
        ),
    )

    private fun hiddenSwitchSubpageScreen(): SettingScreen = SettingScreen(
        pkg = "com.google.android.settings.intelligence",
        windowId = 818,
        isNewWindow = true,
        windowLeft = 760,
        windowTop = 59,
        windowRight = 2880,
        windowBottom = 1709,
        tree = UiNode(
            ref = 300,
            cls = "android.widget.FrameLayout",
            left = 760,
            top = 59,
            right = 2880,
            bottom = 1709,
            children = listOf(
                UiNode(ref = 301, desc = "充电优化", left = 760, top = 124, right = 2880, bottom = 228),
                UiNode(
                    ref = 302,
                    clickable = true,
                    desc = "",
                    left = 786,
                    top = 124,
                    right = 877,
                    bottom = 228,
                    children = listOf(UiNode(ref = 303, cls = "android.widget.ImageButton", clickable = true, desc = "返回", left = 786, top = 124, right = 877, bottom = 228)),
                ),
                UiNode(
                    ref = 304,
                    id = "com.google.android.settings.intelligence:id/recycler_view",
                    left = 760,
                    top = 228,
                    right = 2880,
                    bottom = 1245,
                    children = listOf(
                        UiNode(
                            ref = 305,
                            clickable = true,
                            left = 995,
                            top = 228,
                            right = 2645,
                            bottom = 842,
                            children = listOf(UiNode(ref = 306, desc = "Animation", left = 1485, top = 291, right = 2154, bottom = 778)),
                        ),
                        UiNode(
                            ref = 307,
                            left = 760,
                            top = 1017,
                            right = 2880,
                            bottom = 1242,
                            children = listOf(
                                UiNode(ref = 308, text = "此设置有助于延长电池寿命。", left = 799, top = 1089, right = 2841, bottom = 1138),
                                UiNode(ref = 309, id = "com.google.android.settings.intelligence:id/settingslib_learn_more", text = "详细了解“充电上限为 80%”", left = 799, top = 1159, right = 1230, bottom = 1203),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}

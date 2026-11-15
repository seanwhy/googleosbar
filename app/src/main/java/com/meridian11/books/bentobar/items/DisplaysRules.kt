package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.util.Sym
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure rules for the Displays item: connected screens, resolution shorthand ("4K 60Hz"),
 * keep-awake-while-docked condition, and debug staging. No Android imports.
 */
object DisplaysRules {
    data class Screen(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val hz: Int,
        val hdr: Boolean = false,
        val builtIn: Boolean = id == 0,
    )

    class Bar(
        val icon: String,
        val filled: Boolean,
        val text: String?,
        val active: Boolean,
        val desc: String,
        val tooltip: String,
    )

    /** Compact resolution label for the bar: "4K", "1440p", "1080p", etc. */
    fun shortRes(width: Int, height: Int): String {
        val longSide = max(width, height)
        val shortSide = min(width, height)
        return when {
            shortSide >= 2880 || longSide >= 5120 -> "5K"
            shortSide in 2100..2400 && longSide in 3800..4100 -> "4K"
            shortSide in 1400..1650 && longSide >= 3400 -> "UWQHD"
            shortSide in 1400..1650 -> "1440p"
            shortSide in 1050..1220 -> "1080p"
            shortSide in 700..800 -> "720p"
            else -> "${width}×${height}"
        }
    }

    /** What the bar shows for the connected [screens]. */
    fun bar(
        screens: List<Screen>,
        mode: String,
        noneDesc: String,
        connectedDesc: (Int, String) -> String,
    ): Bar {
        val externals = screens.filterNot { it.builtIn }
        if (externals.isEmpty()) {
            return Bar(Sym.DESKTOP_WINDOWS, filled = false, text = null, active = false, desc = noneDesc, tooltip = noneDesc)
        }
        val first = externals.first()
        val res = shortRes(first.width, first.height)
        val single = when (mode) {
            "res" -> res
            "count" -> externals.size.toString()
            else -> "$res ${first.hz}Hz"
        }
        val text = if (externals.size > 1 && mode != "count") "${externals.size} · $single" else single
        val summary = externals.joinToString(", ") { "${shortRes(it.width, it.height)} ${it.hz}Hz" }
        val desc = connectedDesc(externals.size, summary)
        return Bar(Sym.DESKTOP_WINDOWS, filled = true, text = text, active = true, desc = desc, tooltip = desc)
    }

    /**
     * Whether a Displays item asks to keep the screen awake while an external display is connected. From
     * the layout alone: the item may be folded behind ‹, or the bar hidden, with nothing sampling it.
     */
    fun ruleOn(items: List<ItemConfig>): Boolean = items.any { it.type == "displays" && it.optBool("awake", false) }

    /** Whether the screen is to be kept awake: [ruleOn], with an external display connected. */
    fun wantAwake(items: List<ItemConfig>, screens: List<Screen>): Boolean = screens.any { !it.builtIn } && ruleOn(items)

    /** Debug staging (`displays stage 4k|dual|none`). */
    fun staged(name: String): List<Screen>? = when (name) {
        "4k" -> listOf(
            Screen(0, "Built-in", 2560, 1600, 120, hdr = false, builtIn = true),
            Screen(1, "External 4K", 3840, 2160, 60, hdr = true, builtIn = false),
        )
        "dual" -> listOf(
            Screen(0, "Built-in", 2560, 1600, 120, hdr = false, builtIn = true),
            Screen(1, "Studio Display", 3840, 2160, 60, hdr = true, builtIn = false),
            Screen(2, "Portable 1080p", 1920, 1080, 60, hdr = false, builtIn = false),
        )
        "none" -> listOf(
            Screen(0, "Built-in", 2560, 1600, 120, hdr = false, builtIn = true),
        )
        else -> null
    }

    fun roundHz(hz: Float): Int = hz.roundToInt().coerceAtLeast(1)
}

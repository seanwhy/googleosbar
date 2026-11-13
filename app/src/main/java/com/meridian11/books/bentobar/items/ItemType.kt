package com.meridian11.books.bentobar.items

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online

/** How an item's text is coloured in the bar. */
enum class Tone { NORMAL, ACCENT, WARN, ALERT }

/**
 * How a flight stands for someone who glances at it: nobody has said (no claim is made of it), it
 * goes to plan, it is late, very late, or it will not arrive as planned. The bar colors a flight's
 * line by it.
 */
enum class Stands { NO_CLAIM, GOOD, LATE, VERY_LATE, WILL_NOT_ARRIVE }

/**
 * A slider drawn in the bar in the text's place (the Sound item's "Slider in the bar"). The strip
 * draws it and reports where it is dragged ([ItemType.onSlide]); the item says what there is to draw.
 */
@androidx.compose.runtime.Immutable
data class BarSlider(
    /** How full, 0 to 1. Muted, it is the level that was kept (the system reports 0 then). */
    val level: Float,
    /** How many steps the whole range has (15 for a volume of 0 to 15), so a drag moves in real steps; 0: any level. */
    val steps: Int = 0,
    /** Muted: the kept level is drawn dimmed. */
    val dimmed: Boolean = false,
)

/**
 * A route line drawn in the bar in the icon's place (the Flight item's, while its flight is near or
 * under way): the slider's line with a plane on it. Not a control: the strip only draws it, and the
 * item says where the plane stands and how the flight does.
 */
@androidx.compose.runtime.Immutable
data class BarRoute(
    /** How much of the way lies behind the plane, 0 to 1: where it stands on the line. */
    val share: Float,
    /** What the plane and the part flown are colored by. */
    val stands: Stands = Stands.NO_CLAIM,
    /** The plane is the crossed-out one, and stands upright: the flight is canceled. */
    val struck: Boolean = false,
    /** The part ahead takes the color too, so the line is one color from end to end: the flight will not arrive as planned. */
    val whole: Boolean = false,
)

/** Visual state of a [QuotaRing] badge in the strip. */
enum class QuotaRingStatus { NORMAL, TIMEOUT, UNAVAILABLE }

@androidx.compose.runtime.Immutable
data class QuotaRing(
    /** Outer ring remaining percentage (0..100), e.g., Weekly remaining. */
    val outerPercent: Int = 0,
    /** Optional inner ring remaining percentage (0..100), e.g., 5-hour remaining. */
    val innerPercent: Int? = null,
    /** Normal quota progress, network timeout indicator, or unconfigured/unavailable state. */
    val status: QuotaRingStatus = QuotaRingStatus.NORMAL,
)

/** What one item shows right now. Immutable (never mutate [image]), so Compose can skip unchanged items. */
@androidx.compose.runtime.Immutable
data class ItemState(
    val icon: String? = null,
    /** Filled glyphs match the system's status bar icons; outlined reads as "off". */
    val filled: Boolean = true,
    val text: String? = null,
    /** In a menu's list of items, instead of [text] when that wouldn't fit: a meeting's title without its countdown. */
    val label: String? = null,
    /** Spoken description and tooltip. */
    val desc: String = "",
    /** "Has something to say": hidden items with whenActive pop into the bar while this is true. */
    val active: Boolean = false,
    val tone: Tone = Tone.NORMAL,
    /** An app icon instead of a symbol. */
    val image: Bitmap? = null,
    /** Spacers: a fixed gap in dp (text and icon unused). */
    val gapDp: Int = 0,
    /** Calendar: today's day number, drawn as a small date badge instead of [icon] in the bar. */
    val dayNumber: Int? = null,
    /** AI Quota: concentric dual-track ring with centered remaining percentage. */
    val quotaRing: QuotaRing? = null,
    /** Spacers: draw a thin divider line. */
    val divider: Boolean = false,
    /**
     * For text that ticks (speeds, timers): the bar keeps the widest width seen for a while, so
     * neighbours don't jump every second. A different key (e.g. a timer that says "Done") starts over.
     * Null: always the natural width.
     */
    val widthKey: String? = null,
    /** A slider in the bar instead of [text]; the text stays what menus list and what [desc] says. Null: none. */
    val slider: BarSlider? = null,
    /**
     * A route line in the bar instead of [icon]; the icon stays what menus list. Null: none. An item
     * that is shown as text alone has none to give: its words then keep their own [tone].
     */
    val route: BarRoute? = null,
    /** The tooltip, instead of the type's name: a track's whole title and artist, which flight this is. */
    val tooltip: String? = null,
    /**
     * The most characters [text] has by the item's own rule (20 for the newer items, a title's chosen
     * length): the bar then never draws it wider than 8.5 dp a character, which a count alone can't
     * promise for wide scripts. 0: drawn as wide as it is.
     */
    val textLimit: Int = 0,
    /**
     * The texts an item takes turns showing (Stocks: one stock after another), [text] among them. Its
     * place in the bar is as wide as the widest of them, so nothing next to it moves when the turn
     * changes. Null: none.
     */
    val turns: List<String>? = null,
) {
    /** For logs and dumps: never the words themselves, which can be a track's title, a city or a flight. */
    override fun toString() = "ItemState(icon=${icon != null}, text of ${text?.length ?: 0}, active=$active, tone=$tone)"
}

/** Things a menu can do besides its own content. */
interface MenuHost {
    fun close()
    /** Opens BentoBar's settings on this item. */
    fun openItemSettings(id: String)
    /** Runs [action] after the menu has gone (e.g. before a screenshot). */
    fun afterClose(action: () -> Unit)
}

/**
 * Where Add lists a type, and under which heading. A new type states its group and is listed: nothing else names types by
 * hand. The only group that says so goes online, and every type that goes online is in it (`ItemCatalogTest`).
 */
enum class ItemGroup(@StringRes val title: Int, @StringRes val caption: Int? = null) {
    SYSTEM(R.string.add_group_system),
    TIME(R.string.add_group_time),
    TOOLS(R.string.add_group_tools),
    ONLINE(R.string.add_group_online, R.string.add_group_online_caption),
}

/**
 * One kind of bar item (network speed, timer, …). Types are stateless objects; per-instance
 * settings live in [ItemConfig.options], shared live data in [Env]. A new type is one file and one
 * line in [Items.all]; `ItemCatalogTest` fails for whatever else was forgotten.
 */
abstract class ItemType(
    val type: String,
    @StringRes val titleRes: Int,
    val icon: String,
    /** One line for the catalog. */
    @StringRes val blurbRes: Int,
) {
    /** The name in the app's language, read when asked (never cached, so it follows a locale change). */
    val title: String get() = Env.app.getString(titleRes)
    val blurb: String get() = Env.app.getString(blurbRes)

    /** Where Add lists it. */
    abstract val group: ItemGroup

    /** Its reading goes up and down around the user's line (speed, load): "has something to say" is held for a while ([TickRules.held]), so it doesn't blink. */
    open val holds: Boolean = false

    /** Still shown while presenting, while it has something to say: a running timer, a meeting about to start ([com.meridian11.books.bentobar.data.BarConfig.shows]). */
    open val presents: Boolean = false

    /** Asks for where the device is (Weather's and Air quality's My location), so that is known only while such an item is outside Off (`HereRules.wanted`). */
    open val usesMyLocation: Boolean = false

    /** How often [state] is recomputed while the bar shows. */
    open val refreshMs: Long = 1000

    /** Width of the drop-down menu. */
    open val menuWidthDp: Int = 300

    /** Whether "show when active" means something for this type. */
    open val canBeActive: Boolean = false
    /** Drawn one way only (Tools' icon, a Spacer's gap), so neither the settings nor the right-click menu offer "Show as". */
    open val iconOnly: Boolean = false

    /** The "Show when…" rule in words, for types that [canBeActive]. */
    open val trigger: Trigger? = null

    /** Runtime permissions the type needs to show its data (asked from settings). */
    open val permissions: List<String> = emptyList()

    /**
     * The online service this type asks, or null: the type never goes online. A request reaches a service only while an
     * item of a type that names it is outside Off, the service is switched on for this install and something shows items
     * (see `Http.allowed`). A type that names one is in [ItemGroup.ONLINE], says what it sends in [onlineNote], and Setup
     * has a switch for the service.
     */
    open val online: Online.Service? = null

    /** What About says this type sends, for a type that goes [online] ("About" has one line for each). */
    @get:StringRes open val onlineNote: Int? = null

    /** Shows more with Android's notification access (Now playing): the item's settings offer it. */
    open val notificationAccess: Boolean = false

    /**
     * Speaks up now and then (Now playing, Device batteries, Heat): Add puts it in "When active"
     * with its rule on, and the second button adds it always shown.
     */
    open val addsWhenActive: Boolean = false

    /** Samplers of other types this one reads ("cpu", "battery", …): they run while an item of this type is sampled. */
    open val samples: Set<String> = emptySet()

    /** What it shows is the user's own business (a track's title): debug output says how long the text is, not what it says. */
    open val discreet: Boolean = false

    /**
     * What this item shows now. Asked only for an item the [Ticker] samples, and while it has been told [onLive]; but the
     * debug hooks and the settings can ask in passing, for a type nobody samples. So this must read what is at hand and
     * must not start a listener, a load or anything that outlives the call: those hang on [onLive], or on a [Refresher]'s
     * want(), which nothing feeds while no item is sampled.
     */
    abstract fun state(item: ItemConfig): ItemState

    /**
     * The first item of this type is being sampled: it could show in the bar, its menu is open, or
     * the settings preview is. Register listeners here; main thread. See [onIdle].
     */
    open fun onLive() {}

    /**
     * The last item of this type stopped being sampled: the bar is hidden, the screen is off, or no
     * such item is outside Off. Let go of every listener and poll; nothing of this type runs until
     * [onLive] is called again. Main thread.
     */
    open fun onIdle() {}

    /** Once a second while live, before [state]: only what is cheap. Slow work belongs to a [Refresher]. Main thread. */
    open fun sample(now: Long) {}

    /** A primary click the item handles itself (true), instead of opening its menu. */
    open fun onClick(item: ItemConfig): Boolean = false

    /** Mouse wheel over the item; [steps] > 0 is up/away. */
    open fun onScroll(item: ItemConfig, steps: Int) {}
    /** Uses the mouse wheel itself ([onScroll]); over other items the wheel reveals or folds hidden items. */
    open val usesWheel: Boolean get() = false

    /**
     * The slider in the bar ([ItemState.slider]) was set to [level], 0 to 1 and already on one of its
     * steps; [done] when the pointer let go.
     */
    open fun onSlide(item: ItemConfig, level: Float, done: Boolean) {}

    /**
     * This type's online service was switched off, or its key removed: drop everything it sent, here
     * and on disk. A [Refresher] made for the service has forgotten already. Main thread.
     */
    open fun forgetFetched() {}

    /**
     * Debug builds, from adb (`./bento debug <type> <args>`): stage a state for a test. Returns a line
     * for the log, or null for a command it doesn't know. Release builds have nothing that calls it.
     */
    open fun debug(args: List<String>): String? = null

    /**
     * What [debug] can stage in this type's menu, each as the words that follow the type's name (`stage rain-soon`); `off` ends it.
     * The test that composes every item's menu goes through these, in this order, under the registered types. Each must be answered
     * (a hook says null to what it doesn't know, or can't do for the item it finds) and must change what the menu draws, or the test
     * fails: a state that is named here and never reached would guard nothing. So the order matters where one stage hides another.
     */
    open val stages: List<String> = emptyList()

    /** What an item of this type needs in its options for [stages] to have something to show, merged into the one the test composes. */
    open val stageOptions: Map<String, String> = emptyMap()

    /** The drop-down menu. Null means the type has none (clicks go to [onClick]). */
    open val menu: (@Composable (item: ItemConfig, host: MenuHost) -> Unit)? = null

    /**
     * [item]'s menu is about to open, before its first composition: for what the menu must not read as it stands. In
     * an effect it would come after the first frame, and in composition it would be a side effect. Main thread.
     */
    open fun menuOpening(item: ItemConfig) {}

    /** Type-specific settings, shown in BentoBar's settings. */
    open val options: (@Composable (item: ItemConfig, set: (ItemConfig) -> Unit) -> Unit)? = null

    /** The heading over [options] in settings: "Options", unless the first thing there has a name of its own (the flight item's key). */
    @get:StringRes open val optionsTitle: Int get() = com.meridian11.books.bentobar.R.string.detail_options

    open fun defaultOptions(): Map<String, String> = emptyMap()
}

/**
 * When a hidden item with "show when active" pops into the bar, in words. [sentence] is the long
 * form for the item's settings ("Show when CPU load is above %1$s"), [short] the row detail
 * ("shows above %1$s CPU"). Both take the [threshold]'s value, formatted, as %1$s; rules without a
 * threshold take no argument.
 */
open class Trigger(@StringRes val sentence: Int, @StringRes val short: Int, val threshold: Threshold? = null) {
    fun sentence(item: ItemConfig, value: Int? = null): String = text(sentenceRes(item), item, value)
    fun short(item: ItemConfig): String = text(shortRes(item), item, null)

    /** The long form for [item]: another sentence where an option changes the rule (the CPU item's "or the device runs hot"). */
    @StringRes open fun sentenceRes(item: ItemConfig): Int = sentence
    @StringRes open fun shortRes(item: ItemConfig): Int = short
    private fun text(@StringRes id: Int, item: ItemConfig, value: Int?): String =
        threshold?.let { Env.str(id, it.format(value ?: it.shown(item))) } ?: Env.str(id)
}

/**
 * A number the user picks for a [Trigger], stored as [key] in [ItemConfig.options]. [default] applies
 * while the key is missing (configs saved before it existed), so it must match the old behavior. The value is read as it
 * is stored ([of]): an out-of-range value from an older config keeps working, unlike an [IntOpt].
 */
class Threshold(key: String, default: Int, val range: IntRange, val step: Int = 1, val format: (Int) -> String) : Opt<Int>(key, default, { it.toIntOrNull() }) {
    /** For the slider and the words: the stored value, kept in range. */
    fun shown(item: ItemConfig): Int = of(item).coerceIn(range)
    fun snap(v: Float): Int = (range.first + Math.round((v - range.first) / step) * step).coerceIn(range)
}

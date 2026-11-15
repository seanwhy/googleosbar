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
    /** [image] takes the strip's text colour, as a symbol does (a strip of other apps' small icons, which are masks). */
    val tintImage: Boolean = false,
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
    /**
     * Runs [action] once when this popup starts to close (before the fold), whatever closes it: its own button, Esc, a
     * press on its item, another popup, the bar hiding. For what must end the moment the popup is on its way out (a
     * camera). A host without a window never calls it. A popup that ran it is never turned round: a click while it
     * folds opens a new one.
     */
    fun onClosing(action: () -> Unit) {}
}

/**
 * The accesses the newer types cannot work without, each given in Android's own settings (the camera in its own dialog
 * first). [Accesses] says each in words, asks Android whether it is given and opens where it is.
 */
enum class Access { CAMERA, NOTICES, DO_NOT_DISTURB, WRITE_SETTINGS }

/** Moments at which a type gives back what outlives the bar being on screen (a mute, a Do Not Disturb rule, a light). */
enum class Settle {
    /** The accessibility service has started (after Env.init). Not sent under tests' Env.init, nor from the settings app alone. */
    SERVICE_STARTED,
    /** The service is ending: an unbind, also a plain rebind; never sent when the process is killed. */
    SERVICE_ENDING,
    /** The type's last item has left the layout (it was removed). An Undo may bring it back. */
    LEFT_LAYOUT,
}

/**
 * Where the library lists a type, and under which heading. A new type states its group and is listed: nothing else names types by
 * hand. The only group that says so goes online, and every type that goes online is in it (`ItemCatalogTest`).
 */
enum class ItemGroup(@StringRes val title: Int, @StringRes val caption: Int? = null) {
    SYSTEM(R.string.add_group_system),
    TIME(R.string.add_group_time),
    TOOLS(R.string.add_group_tools),
    ONLINE(R.string.add_group_online, R.string.add_group_online_caption),
}

/**
 * A service that gave no answer, as the settings window says it. [text]: the notice. [lastAt]: when what the item still
 * shows was fetched, 0 if it shows nothing. [ready]: a press of Try again asks now; else it is dimmed (a try is on its
 * way, or one has just failed).
 */
data class Trouble(@param:StringRes val text: Int, val lastAt: Long, val ready: Boolean)

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

    /** Where the library lists it. */
    abstract val group: ItemGroup

    /** Its reading goes up and down around the user's line (speed, load): "has something to say" is held for a while ([TickRules.held]), so it doesn't blink. */
    open val holds: Boolean = false

    /** Still shown while presenting, while it has something to say: a running timer, a meeting about to start ([com.meridian11.books.bentobar.data.BarConfig.shows]). */
    open val presents: Boolean = false

    /** Asks for where the device is (Weather's and Air quality's My location), so that is known only while the layout has such an item (`HereRules.wanted`). */
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
     * The online service this type asks, or null: the type never goes online. A request reaches a service only while the
     * layout has an item of a type that names it, the service is switched on for this install and something shows items
     * (see `Http.allowed`). A type that names one is in [ItemGroup.ONLINE], says what it sends in [onlineNote], and Setup
     * has a switch for the service.
     */
    open val online: Online.Service? = null

    /** What About says this type sends, for a type that goes [online] ("About" has one line for each). */
    @get:StringRes open val onlineNote: Int? = null

    /** Shows more with Android's notification access (Now playing): the item's settings offer it. */
    open val notificationAccess: Boolean = false

    /**
     * The accesses this type cannot work without ([Access]). Setup has a row for each that a registered type needs, and the
     * item's options a line with its button while it is not given. The older types keep [permissions] and
     * [notificationAccess]; only the newer ones use this.
     */
    open val needs: List<Access> = emptyList()

    /**
     * Speaks up now and then (Now playing, Device batteries, Heat): the library's + puts it under the
     * line with its rule on, and its row there says so; "Always" in its options keeps it in the bar.
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
     * The last item of this type stopped being sampled: the bar is hidden, the screen is off, or the
     * layout has no such item. Let go of every listener and poll; nothing of this type runs until
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

    /**
     * What the settings window says of [item] when its service gave no answer, in the item's row and over its options;
     * null while there is nothing to say. It asks nothing.
     */
    open fun trouble(item: ItemConfig, now: Long): Trouble? = null

    /** A press of Try again in that notice. */
    open fun tryAgain(item: ItemConfig) {}

    /** Type-specific settings, shown in the item's options in BentoBar's settings, on the window's own controls (ui/Controls.kt). */
    open val options: (@Composable (item: ItemConfig, set: (ItemConfig) -> Unit) -> Unit)? = null

    open fun defaultOptions(): Map<String, String> = emptyMap()

    /**
     * The item [id] of this type is gone from the layout and no Undo can bring it back (it was removed, and the Bar
     * page's Undo has gone on to something else; or the bar's own menu removed it, which has no Undo): what the type
     * kept for it outside the layout, under its id (the flight a Flight item follows, a Prompter's notes), goes. Main
     * thread. Only for deleting kept text: it runs late or never (Undo is in memory, and a pasted layout tells nobody),
     * so a type that keeps something also sweeps at the start. "The item was removed" is [settle].
     */
    open fun forgetOwn(id: String) {}

    /**
     * This type's items in the layout and any the Bar page's Undo can still bring back: what a type keeps under another
     * id belonged to an item that is gone. Main thread.
     */
    internal fun ids(): Set<String> =
        com.meridian11.books.bentobar.data.Store.config.value.items.filter { it.type == type }.mapTo(HashSet()) { it.id }
            .also { it += com.meridian11.books.bentobar.ui.Undo.held(type) }

    /**
     * Gives back what outlives the bar being on screen (a mute, a Do Not Disturb rule, a keyboard's light), at the moments
     * [Settle] names; the bar merely hiding is none of them. Main thread, behind `Ticker.guarded`. Never throws; disk
     * work goes to the background, as `FlightItem.sweepAtStart` does.
     */
    open fun settle(why: Settle) {}

    /**
     * Its popup stays when the user presses elsewhere (a mirror, a prompter): that press gives the keys back to the app
     * below, and a press inside takes them again. Its own close, Esc, a press on its item, another item's popup, the bar
     * hiding and its strip going still close it; pointing at another item does not, with "Switch popups on hover" on.
     */
    open val staysOpen: Boolean = false

    /**
     * Types that are kept live ([onLive], [sample], [onIdle]) while [item] is sampled, though no item of theirs is. [state]
     * is NOT run for them: what they have to say reaches [item]'s type through [liveNow].
     */
    open fun wakes(item: ItemConfig): Set<String> = emptySet()

    /** Can stand in the Live activity item ([liveNow]). */
    open val liveSource: Boolean = false

    /**
     * What is happening right now, or null. [items]: this type's items (may be empty: the type was only
     * woken). [now] is the clock [sample] gets; [LiveNow.since] is in it. Called from the Live activity's sample on the
     * tick. Reads what is at hand, and may ask for its own refresh without waiting (`Refresher.want`, `Calendar.refresh()`),
     * since nobody runs [state] for a woken type.
     */
    open fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? = null
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

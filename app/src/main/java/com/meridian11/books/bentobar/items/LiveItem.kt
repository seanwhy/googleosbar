package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live activity: the one thing happening right now, from whichever types can say so ([ItemType.liveSource]): a call, a
 * timer that has rung, a meeting about to start, a running timer, a Focus session, a flight from boarding to landing,
 * another app's progress, what plays. The highest rank stands in the bar ([LiveRules.first]); the popup lists them all.
 *
 * It names no source: they are the registered types that say they are one, each with a switch in the item's options. A
 * source switched on is kept live with the item ([wakes]: its `onLive`, `sample` and `onIdle`, never its `state`), so
 * [sample] asks each for its [ItemType.liveNow], at the pace its own item would be recomputed, and keeps the answers in
 * memory; [state] reads them. What they say can be a track's title or another app's words: it is never logged, never
 * printed by the debug hook and gone when the item goes idle ([discreet]).
 */
object LiveItem : ItemType("live", R.string.item_live_title, Sym.RADIO_BUTTON_CHECKED, R.string.item_live_desc) {
    override val group = ItemGroup.TIME
    override val canBeActive = true
    override val addsWhenActive = true
    override val discreet = true
    override val menuWidthDp = 340
    override val trigger = Trigger(R.string.trigger_live, R.string.trigger_live_short)

    /**
     * What the item reaches for, behind one seam: the registry ([types]), the items the ticker samples now ([sampled]), the
     * layout ([layout]), the guard a source's own code runs behind ([guarded]), the words for nothing happening ([idle]),
     * and drawing the item again ([redraw]). A test gives it fakes.
     */
    internal class Wiring(
        val types: () -> List<ItemType>,
        val sampled: () -> List<ItemConfig>,
        val layout: () -> List<ItemConfig>,
        val guarded: (ItemType, () -> LiveNow?) -> LiveNow?,
        val idle: () -> String,
        val redraw: () -> Unit,
    )

    internal var wiring = Wiring(
        types = { Items.all },
        sampled = { Ticker.sampled },
        layout = { Store.config.value.items },
        // Behind the source's own guard: a discreet one's failure is logged by its kind alone, as its state()'s would be.
        guarded = { t, ask -> Ticker.guarded(t, "liveNow", ask) },
        idle = { Env.str(R.string.live_none) },
        redraw = { Ticker.refresh(type) },
    )

    /** The switch of the source [type] in an item's options. */
    internal fun switch(type: String) = Opt.bool(LiveRules.key(type), LiveRules.onAtFirst(type))

    /** The registered types that can stand here, in the registry's order: one switch each in the options. */
    internal fun sources(): List<ItemType> = wiring.types().filter { it.liveSource }

    /** The sources [item] has switched on: they are kept live with it. */
    override fun wakes(item: ItemConfig): Set<String> = sources().filter { switch(it.type).of(item) }.mapTo(LinkedHashSet()) { it.type }

    /**
     * What each source said when it was last asked, by its type: written by [sample] on the main thread (the ticker's), read
     * by [state] there and by the popup. Every source's answer is here, also one that a single item switched off.
     */
    private val answers = MutableStateFlow<Map<String, LiveNow>>(emptyMap())
    internal val heard: StateFlow<Map<String, LiveNow>> get() = answers

    /** When each source was last asked, in [sample]'s clock. Main thread. */
    private val askedAt = HashMap<String, Long>()

    override fun sample(now: Long) {
        // The sources some sampled Live item has switched on: the ones the ticker woke, or that are live of their own.
        val wanted = wiring.sampled().filter { it.type == type }.flatMapTo(HashSet()) { wakes(it) }
        val before = answers.value
        val next = HashMap<String, LiveNow>()
        val placed by lazy { wiring.layout() }
        for (source in sources()) {
            val t = source.type
            if (t !in wanted) continue
            if (!LiveRules.due(now, askedAt[t], source.refreshMs)) { before[t]?.let { next[t] = it }; continue }
            askedAt[t] = now
            wiring.guarded(source) { source.liveNow(now, placed.filter { it.type == t }) }?.let { next[t] = it }
        }
        askedAt.keys.retainAll(wanted)
        answers.value = next
    }

    /** Nobody shows it: nothing of what was happening is kept. */
    override fun onIdle() {
        answers.value = emptyMap()
        askedAt.clear()
    }

    /** What [item] shows, first or not: a staged list, or what its switched-on sources said. */
    internal fun shown(item: ItemConfig): List<LiveNow> = staging.value ?: on(item, answers.value)

    /** Of [answers], those of the sources [item] has switched on. */
    internal fun on(item: ItemConfig, answers: Map<String, LiveNow>): List<LiveNow> = answers.filter { (t, _) -> switch(t).of(item) }.values.toList()

    override fun state(item: ItemConfig): ItemState {
        val first = LiveRules.first(shown(item))
            ?: return ItemState(icon = Sym.RADIO_BUTTON_CHECKED, filled = false, desc = wiring.idle())
        // A running timer ticks: its place keeps its widest width while it is the one shown, and starts over with another.
        return ItemState(icon = first.icon, text = TextRules.short(first.text, LiveRules.LIMIT), desc = first.desc, active = true, tone = first.tone,
            widthKey = "live-${first.rank}", textLimit = LiveRules.LIMIT)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> LiveMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> LiveOptions(item, set) }

    // ---- tests on a device (debug builds' adb hook)

    private val staging = MutableStateFlow<List<LiveNow>?>(null)
    private var stagedAs: String? = null
    /** What the debug hook staged, in place of what the sources said; null: the real thing. The popup collects it. */
    internal val staged: StateFlow<List<LiveNow>?> get() = staging

    override val stages = listOf("timer", "meeting", "several", "none").map { "stage $it" }

    /**
     * `live`: which sources said something, by type and rank. `live stage timer|meeting|several|none`: a fixed list from
     * [LiveSamples] in place of theirs, in the bar and in the popup; `live off` ends it. What comes back is logged, so it
     * names types and ranks, never what they said.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") || args == listOf("stage", "off") -> { stage(null, null); said() }
        args.size != 2 || args[0] != "stage" -> null
        else -> LiveSamples.of(args[1])?.let { stage(args[1], it); said() }
    }

    private fun stage(name: String?, list: List<LiveNow>?) {
        stagedAs = name
        staging.value = list
        wiring.redraw()
    }

    private fun said(): String = "staged=${stagedAs ?: "no"} " +
        answers.value.entries.joinToString(" ", prefix = "said=[", postfix = "]") { (t, n) -> "$t:${n.rank}" }
}

/**
 * What a test on a device stages in place of the sources (`./bento debug live stage <name>`): made-up things happening,
 * with fixed times and words, so a staged popup reads the same any day and anywhere. Only the Live item's own list: no
 * other type is touched.
 */
internal object LiveSamples {
    private const val MINUTE = 60_000L

    private fun timer() = LiveNow(LiveRank.TIMER, Sym.TIMER, Fmt.clock(12 * MINUTE + 34_000), Env.str(R.string.timer_left_desc, Fmt.spokenLeft(12 * MINUTE + 34_000)),
        since = 3_000)

    private fun done() = LiveNow(LiveRank.TIMER_DONE, Sym.TIMER, Env.str(R.string.common_done), Env.str(R.string.timer_finished_desc), Tone.ALERT, since = 4_000)

    private fun meeting() = LiveNow(LiveRank.MEETING, Sym.GROUPS, Env.str(R.string.event_next_text, "Design review", Env.str(R.string.event_in, Fmt.duration(5 * MINUTE))),
        Env.str(R.string.event_next_desc, "Design review", Env.str(R.string.event_in, Fmt.spokenMinutes(5 * MINUTE))), Tone.ACCENT, since = 2_000)

    private fun music() = LiveNow(LiveRank.MEDIA, Sym.MUSIC_NOTE, "Morning mix", Env.str(R.string.media_desc_playing, "Morning mix"), since = 1_000)

    fun of(name: String): List<LiveNow>? = when (name) {
        "timer" -> listOf(timer())
        "meeting" -> listOf(meeting())
        "several" -> listOf(music(), done(), meeting())
        "none" -> emptyList()
        else -> null
    }
}

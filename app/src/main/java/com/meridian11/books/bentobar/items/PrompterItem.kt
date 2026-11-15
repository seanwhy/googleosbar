package com.meridian11.books.bentobar.items

import android.os.SystemClock
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.PrompterRules.Run
import com.meridian11.books.bentobar.util.Sym
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Prompter: the user's notes in a popup that stays open, moving up at the pace they speak so the line being read is
 * the one at the top, nearest the camera. A press beside the popup gives the keys back to the app below (a call), a
 * press inside takes them again; while it has them, Space plays and pauses. Nothing moves where nobody reads it: the
 * script stops where it is when the popup goes, and waits there for the next opening.
 *
 * The script is the user's own words ([discreet]): written in the item's options, kept under the item's id in the
 * no-backup directory (`Kept.own("prompter")`) and in memory once read, never in the layout (which Copy settings and a
 * backup carry), and never in a log line or a debug hook's output.
 */
object PrompterItem : ItemType("prompter", R.string.item_prompter_title, Sym.SUBTITLES, R.string.item_prompter_desc) {
    override val group = ItemGroup.TOOLS
    override val staysOpen = true
    override val discreet = true
    override val iconOnly = true
    override val menuWidthDp = 440

    private const val OWN = "prompter"

    /** Where an item's script stands, and the speed it moves at: one value, so a frame never sees the one changed without the other. */
    data class Going(val run: Run, val speed: Int)

    /** The scripts read or typed so far, by item id: "" where nothing is kept. Written on the main thread or by a read on the type's thread. */
    internal val scripts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Where each item's script stands, by item id. Main thread. */
    internal val runs = MutableStateFlow<Map<String, Going>>(emptyMap())

    /** Scripts typed and not written yet. Main thread. */
    private val unsaved = HashSet<String>()

    private val reading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Debug builds: a made-up script and where it stands, in every Prompter item's place (`./bento debug prompter stage NAME`). */
    internal data class Staged(val script: String, val going: Going)
    internal val staged = MutableStateFlow<Staged?>(null)

    /** The clock a run is timed on: it counts only forwards, and a debug build's moved clock doesn't move the script. */
    internal fun clock(): Long = SystemClock.elapsedRealtime()

    /** The script of [id] as far as it has been read, or null: not read yet. "" when nothing is kept. */
    internal fun script(id: String): String? = scripts.value[id]

    /** Reads [id]'s script from where it is kept, off the main thread, unless it is in memory already. */
    internal fun load(id: String) {
        if (id in scripts.value || !reading.add(id)) return
        Background.executor(type).execute {
            val text = runCatching { Kept.own(OWN).read(id)?.text }.getOrNull().orEmpty()
            reading.remove(id)
            // Typed in the meantime: what was typed is newer than what was kept.
            scripts.update { if (id in it) it else it + (id to text) }
        }
    }

    /** The options' field changed [id]'s script to [text]: in memory at once (an open popup shows it), kept by [save]. Main thread. */
    internal fun edit(id: String, text: String) {
        val script = text.take(PrompterRules.MOST_CHARS)
        scripts.update { it + (id to script) }
        unsaved += id
    }

    /** Keeps what was typed for [id], off the main thread: after a pause in typing, and when the field leaves. A blank script keeps nothing. Main thread. */
    internal fun save(id: String) {
        if (!unsaved.remove(id)) return
        val script = scripts.value[id] ?: return
        Background.executor(type).execute {
            runCatching { if (script.isBlank()) Kept.own(OWN).remove(id) else Kept.own(OWN).write(id, script, System.currentTimeMillis()) }
        }
    }

    /** The item was removed and no Undo can bring it back: its script goes, from memory and from the device. */
    override fun forgetOwn(id: String) {
        scripts.update { it - id }
        runs.update { it - id }
        unsaved -= id
        Background.executor(type).execute {
            runCatching { Kept.own(OWN).remove(id) }
            // A read that was on its way when the item went must not bring the script back into memory.
            scripts.update { it - id }
        }
    }


    override fun settle(why: Settle) {
        when (why) {
            // What neither forgetOwn nor anything else got to (the process ended while Undo still held an item) goes now.
            Settle.SERVICE_STARTED -> {
                val keep = ids()
                scripts.update { it.filterKeys { id -> id in keep } }
                Background.executor(type).execute { runCatching { Kept.own(OWN).keepOnly(keep) } }
            }
            // An item Undo brings back has its script, but nowhere to stand: it opens at the top.
            Settle.LEFT_LAYOUT -> runs.value = emptyMap()
            Settle.SERVICE_ENDING -> {}
        }
    }

    /** The speed [item] asks for, from the layout as it is now. */
    internal fun speedOf(id: String): Int = Store.config.value.items.firstOrNull { it.id == id }?.let { PrompterRules.speed.of(it) } ?: PrompterRules.speed.default

    /** Where [id]'s script stands. */
    internal fun going(id: String): Going = staged.value?.going ?: runs.value[id] ?: Going(Run(), speedOf(id))

    private fun set(id: String, going: Going) {
        val s = staged.value
        if (s != null) staged.value = s.copy(going = going) else runs.update { it + (id to going) }
        // The bar says "playing" only while it does.
        if (Ticker.running) Ticker.refresh(type)
    }

    /** Plays or pauses; at its end ([endDp], once the script is laid out) it plays from the top again. Main thread. */
    internal fun toggle(id: String, endDp: Float?) {
        val g = going(id)
        val now = clock()
        set(id, g.copy(run = if (g.run.playing) PrompterRules.pause(g.run, g.speed, now) else PrompterRules.play(g.run, now, endDp)))
    }

    internal fun restart(id: String) = set(id, going(id).copy(run = PrompterRules.restart()))

    /** It has played to its end ([endDp]) and stands there. */
    internal fun stopAtEnd(id: String, endDp: Float) = set(id, going(id).copy(run = PrompterRules.atEnd(endDp)))

    /** The item's speed is [speed] now (its popup's buttons, its options): the place so far is kept at the speed it had. Main thread. */
    internal fun useSpeed(id: String, speed: Int) {
        val g = going(id)
        if (g.speed != speed) set(id, Going(PrompterRules.respeed(g.run, g.speed, clock()), speed))
    }

    /** A step slower ([by] -1) or faster: in the item's options, which the popup follows. Main thread. */
    internal fun faster(item: ItemConfig, by: Int) {
        val next = (PrompterRules.speed.of(item) + by).coerceIn(PrompterRules.speed.range)
        useSpeed(item.id, next)
        Store.updateItem(item.id) { PrompterRules.speed.set(it, next) }
    }

    /** Its popup is going: the script stops where it is. Main thread. */
    internal fun pauseOnLeaving(id: String) {
        val g = going(id)
        if (g.run.playing) set(id, g.copy(run = PrompterRules.pause(g.run, g.speed, clock())))
    }

    override fun menuOpening(item: ItemConfig) = load(item.id)

    // Its symbol only, filled while it plays.
    override fun state(item: ItemConfig): ItemState {
        val playing = going(item.id).run.playing
        return ItemState(icon = Sym.SUBTITLES, active = playing, desc = if (playing) Env.str(R.string.prompter_desc_playing) else "")
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> PrompterMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> PrompterOptions(item, set) }

    // ---- for tests on a device (debug builds)

    /** A made-up talk, long enough to move. */
    private val sample = listOf(
        "Good morning, and thank you for making the time.",
        "Three things today: where we are, what changed since last month, and what we need from you.",
        "Where we are: the first part is done and in use, and the second is halfway.",
        "What changed: the second part needs a week more than we planned, because the tests found two things we had missed.",
        "What we need: a yes or a no on the date by Friday, so the next team can plan around it.",
        "That is all from me. Questions are welcome now or by message afterwards.",
    ).joinToString("\n\n")

    override val stages = listOf("stage paused", "stage playing", "stage ended", "stage empty")

    /**
     * `prompter stage paused|playing|ended|empty` shows a made-up script in every Prompter item's popup, standing a little
     * way in, moving, at its end, or with no script; `prompter off` ends it; bare `prompter` counts what is known. No
     * line says a word of a script.
     */
    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "items=${ids().size} read=${scripts.value.size} playing=${runs.value.count { it.value.run.playing }} staged=${staged.value != null}"
        "stage" -> {
            val speed = PrompterRules.speed.default
            val going = when (args.getOrNull(1)) {
                "paused" -> Staged(sample, Going(Run(offsetDp = 40f), speed))
                "playing" -> Staged(sample, Going(Run(playing = true, startedAt = clock(), offsetDp = 40f), speed))
                // Far past any script's end: it is drawn at its end, and says so.
                "ended" -> Staged(sample, Going(Run(offsetDp = 1_000_000f), speed))
                "empty" -> Staged("", Going(Run(), speed))
                else -> null
            }
            going?.let { staged.value = it; if (Ticker.running) Ticker.refresh(type); "staged ${args[1]}" }
        }
        "off" -> { staged.value = null; if (Ticker.running) Ticker.refresh(type); "staging off" }
        else -> null
    }
}

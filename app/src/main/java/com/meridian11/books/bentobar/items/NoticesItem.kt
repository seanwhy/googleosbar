package com.meridian11.books.bentobar.items

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Notification icons: other apps' notifications as their small icons in the bar, one per app, and the list in its popup
 * (open, dismiss, clear all). Needs Android's notification access for its own entry, [NoticeAccess], which is bound only
 * while this type is live ([onLive] to [onIdle]: an item of its own on something that shows items, or a Live
 * activity item that wakes it) and only with the access given. What it reads is in [Notices], in memory; nothing of it
 * is written, logged or put into the layout ([discreet]).
 *
 * What counts and in which order is [NoticesRules]'s; the popup and the options are drawn in `NoticesMenu.kt`, the
 * pictures in [NoticeIcons].
 */
object NoticesItem : ItemType("notices", R.string.item_notices_title, Sym.NOTIFICATIONS, R.string.item_notices_desc) {
    override val group = ItemGroup.SYSTEM
    override val canBeActive = true
    override val addsWhenActive = true
    override val discreet = true
    override val needs = listOf(Access.NOTICES)
    override val menuWidthDp = 340
    override val liveSource = true
    override val trigger = Trigger(R.string.trigger_notices, R.string.trigger_notices_short)

    /** How many apps' icons the bar shows; the others are "+2" after them. */
    internal val limit = IntOpt("limit", 4, 1..8)
    /** Ongoing ones in the bar too (a download, a call, an app at work in the background). */
    internal val ongoing = Opt.bool("ongoing", false)
    /** The popup shows what each says, not only its app and title. */
    internal val text = Opt.bool("text", true)

    /** How often the access is asked for again while live: given or taken away in Android's settings, it shows within this. */
    private const val ACCESS_EVERY_MS = 2_000L

    /** What the item asks of Android, behind one seam: the device's, or a test's fake. */
    internal interface Outside {
        /** The user has given [NoticeAccess] its access in Android's settings. */
        fun granted(): Boolean
        /** Asks Android to bind the listener. */
        fun bind()
        /** Lets it go, and everything it read. */
        fun release()
        /** The bar draws the item again now. */
        fun redraw()
        /** The listener is bound right now. */
        fun listening(): Boolean
    }

    private object OnDevice : Outside {
        override fun granted() = NoticeAccess.granted(Env.app)
        override fun bind() = NoticeAccess.bind(Env.app)
        override fun release() = NoticeAccess.release()
        override fun redraw() = Ticker.refresh(type)
        override fun listening() = NoticeAccess.connected
    }

    internal var outside: Outside = OnDevice

    // ---- main thread only from here on
    private var started = false
    private var accessOn = false
    /** When the access was last asked for, in [sample]'s clock; null: at [onLive], before the first sample. */
    private var accessReadAt: Long? = null
    /** The strip each item drew last, and the icons it was drawn from: drawn again only when they change. */
    private val strips = HashMap<String, Pair<List<Bitmap?>, Bitmap>>()

    /** A staged sample (debug builds' hook): its name, its notifications, and whether the access counts as given. */
    internal class Staged(val name: String, val notices: List<Notices.Notice>, val granted: Boolean)

    private val staging = MutableStateFlow<Staged?>(null)
    /** What the debug hook staged, in place of Android's; null: the real thing. The popup collects it. */
    internal val staged: StateFlow<Staged?> get() = staging

    /** The listener would be of use right now: [NoticeAccess] leaves again at once when it isn't. */
    internal fun wantsListener(): Boolean = started && accessOn

    /** What there is to show: the staged sample's, or what the listener read. */
    internal fun shown(): List<Notices.Notice> = staging.value?.notices ?: Notices.now.value

    /** The access counts as given: as a staged sample says, else as last asked while live, else asked now. */
    private fun granted(): Boolean = staging.value?.granted ?: if (started) accessOn else outside.granted()

    /** For the popup, which reads the access as Setup last saw it ([given]): a staged sample's word first. */
    internal fun granted(given: Set<Access>): Boolean = staging.value?.granted ?: (Access.NOTICES in given)

    override fun onLive() {
        started = true
        accessOn = outside.granted()
        accessReadAt = null
        if (accessOn) outside.bind()
    }

    override fun sample(now: Long) {
        if (!started) return
        val last = accessReadAt
        if (last == null) { accessReadAt = now; return }
        if (!NowPlayingRules.due(now, last, ACCESS_EVERY_MS)) return
        accessReadAt = now
        val was = accessOn
        accessOn = outside.granted()
        if (accessOn && !was) outside.bind()
        if (!accessOn && was) { outside.release(); Notices.publish(emptyList()) }
    }

    override fun onIdle() {
        started = false
        accessOn = false
        outside.release()
        Notices.publish(emptyList())
        strips.clear()
        NoticeIcons.forget()
    }

    /** The popup asks Setup's word on the access (it reads it observed): fresh, since it may have been given since. */
    override fun menuOpening(item: ItemConfig) = Setup.refresh(Env.app)

    override fun state(item: ItemConfig): ItemState {
        if (!granted()) return ItemState(icon = Sym.NOTIFICATIONS_OFF, filled = false, desc = Env.str(R.string.notices_needs_access))
        val all = shown()
        val bar = NoticesRules.bar(all, ongoing.of(item))
        if (bar.isEmpty()) return ItemState(icon = Sym.NOTIFICATIONS, filled = false, desc = Env.str(R.string.notices_none))
        val icons = bar.take(limit.of(item)).map { it.icon }
        val count = NoticesRules.counted(all, ongoing.of(item)).size
        return ItemState(icon = Sym.NOTIFICATIONS, image = strip(item.id, icons), tintImage = true, text = NoticesRules.more(icons.size, bar.size),
            active = true, desc = Env.plural(R.plurals.notices_desc, count, count, bar.joinToString(", ") { it.app }))
    }

    /** [id]'s picture of [icons], the one drawn before while they are the same pictures (so the strip has nothing to draw anew). */
    private fun strip(id: String, icons: List<Bitmap?>): Bitmap {
        strips[id]?.let { (drawnFrom, picture) -> if (drawnFrom.size == icons.size && drawnFrom.indices.all { drawnFrom[it] === icons[it] }) return picture }
        return NoticeIcons.strip(icons).also { strips[id] = icons to it }
    }

    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val n = NoticesRules.live(shown()) ?: return null
        val percent = Fmt.percent((n.progress ?: 0f).toDouble())
        return LiveNow(LiveRank.PROGRESS, Sym.DOWNLOADING, Env.str(R.string.notices_live, n.app, percent),
            desc = n.title?.let { Env.str(R.string.notices_live_desc, n.app, it, percent) } ?: Env.str(R.string.notices_live, n.app, percent),
            since = now - (Now.wall() - n.postedAt).coerceAtLeast(0), open = { NoticeAccess.open(Env.app, n) })
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> NoticesMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> NoticesOptions(item, set) }

    // ---- tests on a device (debug builds' adb hook)

    override val stages = listOf("three", "many", "progress", "none", "noaccess").map { "stage $it" }

    /**
     * `notices`: one line of counts (`bells`: the small icons that could not be loaded and show as a bell). `notices stage
     * <name>`: a made-up list from [NoticeSamples] in place of what the listener read, in the bar and in the popup;
     * `notices off` ends it. What comes back is logged, so it says how many, never what.
     */
    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") || args == listOf("stage", "off") -> { stage(null); said() }
        args.size != 2 || args[0] != "stage" -> null
        else -> NoticeSamples.of(args[1])?.let { stage(it); said() }
    }

    private fun stage(sample: Staged?) {
        staging.value = sample
        strips.clear()
        outside.redraw()
    }

    private fun said(): String {
        val all = shown()
        return "live=$started access=$accessOn listener=${outside.listening()} staged=${staging.value?.name ?: "no"} " +
            "notices=${all.size} apps=${all.map { it.pkg }.distinct().size} ongoing=${all.count { it.ongoing }} bells=${all.count { it.icon == null }}"
    }
}

/**
 * What a test on a device stages in place of other apps' notifications (`./bento debug notices stage <name>`): made-up
 * apps and words, fixed, so a staged popup reads the same any day. Pure data.
 */
internal object NoticeSamples {
    private fun notice(key: String, app: String, title: String?, text: String?, at: Long, ongoing: Boolean = false, progress: Float? = null) =
        Notices.Notice("staged:$key", "com.example.${app.lowercase()}", app, title, text, postedAt = 1_000_000L + at, ongoing = ongoing,
            clearable = !ongoing, progress = progress, icon = null, open = null)

    private val three = listOf(
        notice("chat", "Chat", "Sam", "Lunch at the usual place?", 30),
        notice("mail", "Mail", "Your order has shipped", "It arrives on Thursday", 20),
        notice("cal", "Calendar", "Team meeting", "In 10 minutes, Room 4", 10),
    )

    private val many = three + listOf(
        notice("chat-2", "Chat", "Robin", "See you there", 40),
        notice("notes", "Notes", "Reminder", "Water the plants", 5),
        notice("photos", "Photos", "Backup done", "128 photos saved", 4),
        notice("files", "Files", "Download complete", "slides.pdf", 3),
        notice("music", "Music", "New album", "From an artist you follow", 2),
        notice("mail-2", "Mail", "Weekly summary", "Five new messages", 1),
    )

    private val progress = listOf(
        notice("download", "Files", "slides.pdf", "Downloading", 50, ongoing = true, progress = 0.62f),
        notice("chat", "Chat", "Sam", "Lunch at the usual place?", 30),
    )

    fun of(name: String): NoticesItem.Staged? = when (name) {
        "three" -> NoticesItem.Staged(name, three, granted = true)
        "many" -> NoticesItem.Staged(name, many, granted = true)
        "progress" -> NoticesItem.Staged(name, progress, granted = true)
        "none" -> NoticesItem.Staged(name, emptyList(), granted = true)
        "noaccess" -> NoticesItem.Staged(name, emptyList(), granted = false)
        else -> null
    }
}

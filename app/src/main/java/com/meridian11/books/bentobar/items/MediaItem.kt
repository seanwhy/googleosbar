package com.meridian11.books.bentobar.items

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * Now playing: what plays, in the bar while it plays and for the rule's minutes after, with the
 * controls in its menu. The source is [NowPlaying], which runs only while an item of this type is
 * live ([onLive], [onIdle]) and knows the players' titles only with notification access; without it
 * the item still shows that something plays and controls it with the media keys.
 *
 * What the bar and the menu say is decided in [MediaText] (pure, tested); the menu and the options
 * are drawn in `MediaMenu.kt`. A title, an artist and artwork are only ever in memory: nothing of a
 * player is written, logged or put into the layout, and this object itself keeps no title, only
 * when something last played and which app that was.
 */
object MediaItem : ItemType("media", R.string.item_media_title, Sym.MUSIC_NOTE, R.string.item_media_desc) {
    override val group = ItemGroup.TOOLS
    override val canBeActive = true
    override val addsWhenActive = true
    override val notificationAccess = true
    // A title is the user's own business: debug output says how long the text is, not what it says.
    override val discreet = true
    override val menuWidthDp = 320

    /** How long the item stays after playback pauses, in minutes: a pause for a call doesn't lose it. */
    private val linger = Threshold(MediaText.OPTION_LINGER, MediaText.DEFAULT_LINGER, MediaText.LINGER) { Env.plural(R.plurals.common_minutes_short, it, it) }
    override val trigger = Trigger(R.string.trigger_media, R.string.trigger_media_short, linger)

    /**
     * [Now.elapsed] when something was last seen playing; 0: never. It outlives [onIdle]: when the bar
     * comes back within the rule's minutes (a full-screen window closed, the lid opened), the pause
     * still shows, and after them it doesn't.
     */
    @Volatile private var playedAt = 0L
    /**
     * The app that was playing then, by its package, which says nothing of what it played. Not the
     * player's key: that can be another one each time the source starts again.
     */
    @Volatile private var playedBy: String? = null
    /** A staged sample's word on whether notification access is on; null: Android is asked. Set by the debug hook only. */
    @Volatile private var stagedGranted: Boolean? = null

    override fun onLive() = NowPlaying.start()
    override fun onIdle() = NowPlaying.stop()
    override fun sample(now: Long) {
        NowPlaying.tick(now)
        val playing = NowPlaying.state.value
        if (playing.active) { playedAt = now; playedBy = playing.main?.pkg }
    }

    /** [MediaText]'s words from the app's strings, each read when it is used, so they follow the app's language. */
    internal val words = MediaText.Words { word ->
        Env.str(when (word) {
            MediaText.Word.NOTHING -> R.string.media_nothing
            MediaText.Word.PLAYING -> R.string.media_playing
            MediaText.Word.PAUSED -> R.string.common_paused
            MediaText.Word.STARTING -> R.string.status_starting
            MediaText.Word.PLAYER_PAUSED -> R.string.media_player_paused
            MediaText.Word.APP_PLAYING -> R.string.media_app_playing
            MediaText.Word.APP_PAUSED -> R.string.media_app_paused
            MediaText.Word.BAR_BOTH -> R.string.media_bar_both
            MediaText.Word.TRACK_BY -> R.string.media_track_by
            MediaText.Word.TRACK_ALBUM -> R.string.media_track_album
            MediaText.Word.POSITION_STATE -> R.string.media_position_state
            MediaText.Word.OTHER_PAUSE -> R.string.media_other_pause
            MediaText.Word.OTHER_PLAY -> R.string.media_other_play
            MediaText.Word.OPEN_PLAYER -> R.string.media_open_player
            MediaText.Word.DESC_PLAYING_BY -> R.string.media_desc_playing_by
            MediaText.Word.DESC_PLAYING -> R.string.media_desc_playing
            MediaText.Word.DESC_PAUSED -> R.string.media_desc_paused
            MediaText.Word.DESC_PLAYING_UNKNOWN -> R.string.media_desc_playing_unknown
            MediaText.Word.DESC_PAUSED_UNKNOWN -> R.string.media_desc_paused_unknown
        })
    }

    // ---- what the item shows

    /** Playing, paused within the rule's minutes, or nothing. */
    internal fun phase(item: ItemConfig, playing: NowPlaying.Playing) = MediaText.phase(playing.active, playedAt, Now.elapsed(), linger.shown(item))

    /** A player as [MediaText] needs it. A player whose app has no name that can be shown goes by its package's. */
    internal fun track(session: NowPlaying.Session) =
        MediaText.Track(session.title, session.artist, MediaText.line(session.app).ifEmpty { session.pkg }, session.album)

    /** Notification access is on in Android, which the menu then doesn't ask for (or a staged sample says so). */
    internal fun granted(): Boolean = stagedGranted ?: NowPlaying.access()

    override fun state(item: ItemConfig): ItemState {
        val playing = NowPlaying.state.value
        val phase = phase(item, playing)
        // Paused, the bar shows what it showed while playing: the words of the app that was playing, and no other's.
        val main = playing.main?.takeIf { MediaText.follows(phase, it.pkg, playedBy) }
        val chars = MediaText.chars(item.options)
        val bar = MediaText.bar(phase, main?.let(::track), MediaText.show(item.options), chars, words)
        return ItemState(
            // Each state has a glyph of its own, always filled: at this size the note looks the same outlined.
            icon = when (bar.glyph) { MediaText.Glyph.NOTE -> Sym.MUSIC_NOTE; MediaText.Glyph.PAUSE -> Sym.PAUSE; MediaText.Glyph.OFF -> Sym.MUSIC_OFF },
            text = bar.text, desc = bar.desc, active = bar.active, tooltip = bar.tooltip,
            // The count decides what is said; the strip also holds the text to 8.5 dp a character, for scripts whose letters are wide.
            textLimit = if (bar.text == null) 0 else chars)
    }

    override val liveSource = true

    /**
     * For the Live activity: what plays, while it plays, by its title (the player's name where it gives none), and only
     * with this item's own access, which the Live activity never asks for ([LiveRules.media]). Woken, [NowPlaying] runs as
     * it does for an item of its own, from [onLive] to [onIdle].
     */
    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val main = LiveRules.media(NowPlaying.state.value) ?: return null
        val track = track(main)
        val text = MediaText.text(track, MediaText.Show.TITLE, LiveRules.LIMIT, words) ?: return null
        return LiveNow(LiveRank.MEDIA, Sym.MUSIC_NOTE, text, MediaText.bar(MediaText.Phase.PLAYING, track, MediaText.Show.TITLE, LiveRules.LIMIT, words).desc,
            since = main.startedAt, open = { NowPlaying.open(main.key) })
    }

    /** With "A click: Plays or pauses" the item acts on a click, as Keep awake does; right-click › Open still reaches the menu. */
    override fun onClick(item: ItemConfig): Boolean {
        if (!MediaText.toggles(item.options)) return false
        NowPlaying.playPause()
        return true
    }

    override val usesWheel = true
    override fun onScroll(item: ItemConfig, steps: Int) = NowPlaying.volume(steps)

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> MediaMenu(item, host) }
    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set -> MediaOptions(item, set) }

    // ---- tests on a device (debug builds' adb hook)

    /**
     * `media`: one line about the source. `media stage <name>`: a sample from [MediaSamples] in place
     * of the real players, in the bar and in the menu; `media stage off` ends it. A staged menu's
     * buttons still send real media keys. What comes back is logged, so it holds no title.
     */
    override val stages = listOf("playing", "paused", "none", "notitle", "two", "live", "noaccess", "noaccess paused", "noaccess none", "starting", "long", "wide", "emoji", "rtl").map { "stage $it" }

    override fun debug(args: List<String>): String? = when {
        args.isEmpty() -> said()
        args == listOf("off") -> { stage(null); said() }
        args[0] != "stage" -> null
        args.drop(1) == listOf("off") -> { stage(null); said() }
        else -> MediaSamples.of(args.drop(1))?.let { stage(it); said() }
    }

    /**
     * The source's line, and how many characters the first Now playing item's text has as "Longest
     * title" counts them (`state media` counts an emoji as two): the number, never the text.
     */
    private fun said(): String {
        val item = Store.config.value.items.firstOrNull { it.type == type }
        return NowPlaying.debugLine() + (item?.let { " chars=" + MediaText.count(state(it).text.orEmpty()) } ?: "")
    }

    private const val STAGED = "staged:"

    private fun stage(sample: MediaSamples.Sample?) {
        val now = Now.elapsed()
        stagedGranted = sample?.granted
        // A sample that paused did so this moment; every other starts clean, so that "none" reads "Nothing playing" at once.
        val paused = sample?.paused == true
        playedAt = if (paused) now else 0
        playedBy = sample?.takeIf { paused }?.players?.firstOrNull()?.pkg
        NowPlaying.stage(sample?.let { s ->
            NowPlaying.Playing(access = s.access, starting = s.starting, audible = s.audible, sessions = s.players.mapIndexed { i, p ->
                NowPlaying.Session(key = STAGED + i, pkg = p.pkg, app = p.app, title = p.title, artist = p.artist, album = p.album, playing = p.playing,
                    durationMs = p.durationMs, positionMs = p.positionMs, positionAt = now, speed = 1f, canPlayPause = true, canNext = p.canNext,
                    canPrevious = p.canPrevious, canSeek = p.canSeek, art = if (p.art) cover() else null, startedAt = if (p.playing) now else 0)
            })
        })
    }

    /** A plain square in a cover's place, for a staged player that has artwork. */
    private fun cover(): Bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF35618E.toInt()) }
}

package com.meridian11.books.bentobar.items

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.util.Now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * What is playing, for the Now playing item: the media players' sessions as one immutable value
 * ([state]), and their controls.
 *
 * Without notification access it knows one thing, whether something is audible
 * ([Playing.audible], from AudioManager), and its controls are the media keys. With it (see
 * [MediaAccess]) it lists the players with title, artist, artwork and position, and controls each
 * by itself.
 *
 * It does nothing unless a Now playing item is live: [start] and [stop] are that item type's
 * `onLive` and `onIdle`, and [tick] its `sample`. Stopped, it holds no listener, no session, no
 * title and no artwork. Titles and artwork are only ever in memory: never written, never logged
 * ([Session.toString] leaves them out). Artwork is what a player hands over as a picture; an
 * address in a session is never fetched.
 */
object NowPlaying {
    private const val TAG = "BentoBar"
    /** Artwork is kept no larger than this (56 dp at twice the density). */
    private const val ART_PX = 112
    /** How often notification access is asked for again while live: turning it on or off shows within this. */
    private const val ACCESS_EVERY_MS = 2_000L
    /** How long "Starting…" may stand while a device binds the listener before sessions can be read. */
    private const val STARTING_MS = 3_000L

    /** One player. Equal while nothing a screen shows has changed; [art] is the same instance while the picture is the same. */
    @Immutable
    data class Session(
        /** Names the session for the controls ([playPause], [open] …). Not for showing. */
        val key: String,
        val pkg: String,
        /** The player's name: "Spotify". */
        val app: String,
        /** One line each; empty when the player doesn't say. */
        val title: String,
        val artist: String,
        val album: String,
        /** Playing, buffering or seeking. */
        val playing: Boolean,
        /** 0: unknown (a live stream). */
        val durationMs: Long,
        /** Where it stood at [positionAt] ([Now.elapsed]); use [position] for where it stands now. */
        val positionMs: Long,
        val positionAt: Long,
        val speed: Float,
        val canPlayPause: Boolean,
        val canNext: Boolean,
        val canPrevious: Boolean,
        val canSeek: Boolean,
        /** At most 112 px a side; null while there is none, or none yet. Never change it. */
        val art: Bitmap?,
        /** [Now.elapsed] when it last started playing; 0 if it was never seen playing. */
        val startedAt: Long,
    ) {
        /** Where the track stands at [now] ([Now.elapsed]): it counts on by the clock while playing. */
        fun position(now: Long): Long = NowPlayingRules.position(positionMs, positionAt, speed, playing, durationMs, now)

        /** No title, artist or album: nothing of what someone listens to may end up in a log line. */
        override fun toString() = "Session(pkg=$pkg, playing=$playing, hasTitle=${title.isNotEmpty()}, hasArt=${art != null})"
    }

    /** Everything the item and its menu show. */
    @Immutable
    data class Playing(
        /** Notification access is on and the players can be listed. */
        val access: Boolean = false,
        /** Access is on, the players can't be listed yet ("Starting…"; 3 s at most). */
        val starting: Boolean = false,
        /** The players, the one the bar follows first (it started playing last). Empty without access. */
        val sessions: List<Session> = emptyList(),
        /** Something is audible, as far as Android's audio system says: all that is known without access. */
        val audible: Boolean = false,
    ) {
        val main: Session? get() = sessions.firstOrNull()
        /** "Other players": up to three. */
        val others: List<Session> get() = sessions.drop(1).take(3)
        /** Something is playing: the first player is, or, where no player is listed, something is audible. */
        val active: Boolean get() = main?.playing ?: audible
    }

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** For the slow parts: a player's first state and metadata, and scaling its artwork. */
    private val work: Executor by lazy {
        ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "BentoBar-media").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    private val current = MutableStateFlow(Playing())
    /** Collected by the menu; read as `state.value` by the item's state. */
    val state: StateFlow<Playing> get() = current

    // ---- main thread only from here on
    private var started = false
    private var listening = false
    /** The listener was asked to be bound, because sessions couldn't be read without (see [listen]). */
    private var askedToBind = false
    private var startingSince = 0L
    private var refused = false
    private var accessOn = false
    private var accessReadAt = 0L
    private var audible = false
    private var staged: Playing? = null
    /** How far a test's staged clock ran ahead when the players' positions were last stamped by it ([publish]); 0 in every release. */
    private var aheadThen = 0L
    private val players = ArrayList<Player>()
    private val labels = HashMap<String, String>()

    /** One session being watched. */
    private class Player(val controller: MediaController) {
        val key: String = controller.packageName + ":" + Integer.toHexString(controller.sessionToken.hashCode())
        var callback: MediaController.Callback? = null
        var playback: PlaybackState? = null
        var metadata: MediaMetadata? = null
        var startedAt = 0L
        var lastPlayedAt = 0L
        var wasPlaying = false
        /** The scaled artwork, the track it belongs to (title, artist, album, length), and the metadata its picture was last asked of. */
        var art: Bitmap? = null
        var artFor: String? = null
        var artOf: MediaMetadata? = null
    }

    fun init(context: Context) { app = context.applicationContext }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> adopt(list.orEmpty()) }
    /** After a player's callback: the new value, and the item drawn from it at once (a pause shows when it is pressed, not a tick later). */
    private val rebuild = Runnable { publish(); Ticker.refresh(MediaItem.type) }

    // ---- life cycle (the item type's onLive, onIdle and sample)

    /** A Now playing item is live: find out what there is, and keep up with it. Main thread. */
    fun start() {
        if (started) return
        started = true
        refused = false
        readAccess(force = true)
        audible = musicActive()
        if (accessOn) listen()
        publish()
    }

    /** No Now playing item is live any more: no listener, no session, no title and no artwork stay behind. Main thread. */
    fun stop() {
        if (!started) return
        started = false
        unlisten()
        if (askedToBind) { askedToBind = false; MediaAccess.release() }
        main.removeCallbacks(rebuild)
        current.value = staged ?: Playing()
    }

    /** Once a second while live: is anything audible, and (every two seconds) is access still what it was. */
    fun tick(now: Long) {
        if (!started) return
        val was = audible
        audible = musicActive()
        val before = accessOn
        readAccess(force = false)
        if (accessOn && !before) { refused = false; listen() }
        if (!accessOn && before) {
            unlisten()
            // Android has let the listener go with the access; given again, it is asked for afresh.
            if (askedToBind) { askedToBind = false; MediaAccess.release() }
        }
        // "Starting…" has stood for three seconds: the listener that was asked for hasn't brought the players, so go on without
        // the titles. The published value is what counts, not the steps that led to it: none of them can leave it standing.
        if (staged == null && current.value.starting && NowPlayingRules.due(Now.elapsed(), startingSince, STARTING_MS, slackMs = 0)) { refused = !listening; publish() }
        // A test moved the staged clock (debug builds): positions stamped by the old one would be off by as much until a player spoke again.
        if (was != audible || before != accessOn || Now.ahead != aheadThen) publish()
    }

    // ---- access

    /** Notification access is on for BentoBar, as last read ([tick] reads it again every two seconds while live). */
    fun access(): Boolean = if (started) accessOn else MediaAccess.granted(app)

    fun openAccessSettings() = MediaAccess.openSettings(app)

    private fun readAccess(force: Boolean) {
        val now = Now.elapsed()
        // Every second tick, also when that one comes a few milliseconds early: access that is taken away shows within two seconds.
        if (!force && !NowPlayingRules.due(now, accessReadAt, ACCESS_EVERY_MS)) return
        accessReadAt = now
        accessOn = MediaAccess.granted(app)
    }

    /** The listener would be of use right now: [MediaAccess] leaves again at once when it isn't. */
    internal fun wantsListener(): Boolean = started && askedToBind

    /**
     * The bound listener reports in: try the sessions again, and publish what came of it. A device may
     * refuse the list to the running listener too: without a new value then, "Starting…" would stand
     * until something else changed. Any thread.
     */
    internal fun listenerConnected() { main.post { if (started && accessOn && !listening) { refused = false; listen(); publish() } } }

    // ---- the sessions

    private fun manager() = app.getSystemService(MediaSessionManager::class.java)

    /**
     * Registers for the list of players. Android allows it to an app that is turned on under
     * Notification access; the listener service itself need not run. A device that refuses all the
     * same (a SecurityException) gets the listener bound once, and the next try comes when it
     * reports in ([listenerConnected]).
     */
    private fun listen() {
        if (listening || !started) return
        val msm = manager() ?: return
        val component = MediaAccess.component(app)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            listening = true
            adopt(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsChanged) }
            listening = false
            if (!askedToBind) {
                Log.i(TAG, "media sessions need a running listener here: asking for it")
                askedToBind = true
                startingSince = Now.elapsed()
                MediaAccess.bind(app)
            } else if (MediaAccess.connected) {
                Log.i(TAG, "media sessions refused with the listener running: going on without titles")
                refused = true
                // It was of no use: no reason to keep it running.
                askedToBind = false
                MediaAccess.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "media sessions unavailable: ${e.javaClass.simpleName}")
            refused = true
        }
    }

    private fun unlisten() {
        if (listening) runCatching { manager()?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        listening = false
        players.forEach { drop(it) }
        players.clear()
    }

    private fun drop(p: Player) {
        p.callback?.let { cb -> runCatching { p.controller.unregisterCallback(cb) } }
        p.callback = null
        p.art = null
        p.artFor = null
        p.artOf = null
        p.metadata = null
    }

    /** The system's list of players changed: watch the new ones, let the gone ones go. */
    private fun adopt(list: List<MediaController>) {
        if (!started || !listening) return
        val gone = players.filter { p -> list.none { it.sessionToken == p.controller.sessionToken } }
        gone.forEach { drop(it) }
        players.removeAll(gone.toSet())
        for (c in list) {
            if (players.any { it.controller.sessionToken == c.sessionToken }) continue
            val p = Player(c)
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { p.playback = state; changed() }
                override fun onMetadataChanged(metadata: MediaMetadata?) { p.metadata = metadata; changed() }
                override fun onSessionDestroyed() { drop(p); players.remove(p); changed() }
            }
            p.callback = cb
            runCatching { c.registerCallback(cb, main) }
            // Whether it plays is asked here and now (a short question to Android itself), so the first value published
            // holds every player's state. Asked in the background, each answer came in a post of its own: for some frames
            // after the bar came back nothing was "playing", and of two playing players the one answered last counted as
            // the one that started last. Now they are all first seen at one moment, and the system's order stands.
            p.playback = runCatching { c.playbackState }.getOrNull()
            players += p
            // What it plays is asked off the main thread: metadata can carry a large picture.
            work.execute {
                val metadata = runCatching { c.metadata }.getOrNull()
                main.post { if (p in players) { if (p.metadata == null) p.metadata = metadata; changed() } }
            }
        }
        // Keep the system's order (the most important player first) as the tie-break.
        players.sortBy { p -> list.indexOfFirst { it.sessionToken == p.controller.sessionToken }.let { if (it < 0) Int.MAX_VALUE else it } }
        changed()
    }

    /** Something about a player changed: one new value for all of it, however many callbacks came at once. */
    private fun changed() {
        main.removeCallbacks(rebuild)
        main.post(rebuild)
    }

    private fun publish() {
        aheadThen = Now.ahead
        staged?.let { current.value = it; return }
        if (!started) { current.value = Playing(); return }
        val readable = accessOn && listening
        val now = Now.elapsed()
        val built = if (readable) players.map { session(it, now) } else emptyList()
        val order = NowPlayingRules.order(players.mapIndexed { i, p ->
            NowPlayingRules.Standing(p.key, built.getOrNull(i)?.playing == true, p.startedAt, p.lastPlayedAt)
        })
        current.value = Playing(
            access = readable,
            starting = accessOn && !listening && askedToBind && !refused,
            sessions = if (readable) order.mapNotNull { key -> built.firstOrNull { it.key == key } } else emptyList(),
            audible = audible,
        )
    }

    private fun session(p: Player, now: Long): Session {
        val playback = p.playback
        val md = p.metadata
        val playing = when (playback?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
            else -> false
        }
        if (playing && !p.wasPlaying) p.startedAt = now
        if (playing || p.wasPlaying) p.lastPlayedAt = now
        p.wasPlaying = playing
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> runCatching { md?.getText(k) }.getOrNull()?.takeIf { it.isNotBlank() } }
        val title = NowPlayingRules.oneLine(text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        val artist = NowPlayingRules.oneLine(text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        val album = NowPlayingRules.oneLine(text(MediaMetadata.METADATA_KEY_ALBUM))
        val duration = runCatching { md?.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrNull()?.coerceAtLeast(0) ?: 0L
        val actions = playback?.actions ?: 0L
        fun can(action: Long) = actions and action != 0L
        artwork(p, md, "$title\n$artist\n$album\n$duration")
        return Session(
            key = p.key, pkg = p.controller.packageName, app = label(p.controller.packageName),
            title = title, artist = artist, album = album, playing = playing, durationMs = duration,
            positionMs = playback?.position?.coerceAtLeast(0) ?: 0L,
            // The system stamps positions with the real clock; a staged clock (debug) is added so the two agree.
            positionAt = (playback?.lastPositionUpdateTime?.takeIf { it > 0 } ?: (now - Now.ahead)) + Now.ahead,
            speed = playback?.playbackSpeed ?: 0f,
            canPlayPause = can(PlaybackState.ACTION_PLAY_PAUSE) || can(PlaybackState.ACTION_PLAY) || can(PlaybackState.ACTION_PAUSE),
            canNext = can(PlaybackState.ACTION_SKIP_TO_NEXT),
            canPrevious = can(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canSeek = can(PlaybackState.ACTION_SEEK_TO) && duration > 0,
            art = p.art.takeIf { p.artFor == "$title\n$artist\n$album\n$duration" },
            startedAt = p.startedAt,
        )
    }

    /**
     * The picture the player handed over with [md], scaled down on the background thread, once for
     * each metadata it sends: a player may send a track's picture a moment after its words, or
     * replace it (a placeholder, the cover of the track before) while the words stay as they are.
     * Until a track's first picture is ready there is none (the menu draws its tile); a later one
     * takes its place when it is ready. A picture that is the same as the one before keeps that
     * instance, so sending the same metadata again draws nothing anew. An address is never used.
     */
    private fun artwork(p: Player, md: MediaMetadata?, track: String) {
        if (p.artOf === md) return
        p.artOf = md
        val source = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { k -> runCatching { md?.getBitmap(k) }.getOrNull() }
        if (source == null) { p.art = null; p.artFor = null; return }
        val before = p.art.takeIf { p.artFor == track }
        work.execute {
            val small = runCatching { scaled(source) }.getOrNull()
            val same = before != null && small != null && runCatching { small.sameAs(before) }.getOrDefault(false)
            main.post {
                // Only the picture of the metadata that came last counts: an earlier one that took longer is dropped.
                if (p in players && p.artOf === md) { p.art = if (same) before else small; p.artFor = track; changed() }
            }
        }
    }

    private fun scaled(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) ?: return null else source
        val scale = ART_PX.toFloat() / maxOf(soft.width, soft.height)
        // Always a copy: the player's own picture is never kept, however small it is.
        return if (scale >= 1f) soft.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt().coerceAtLeast(1), (soft.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { app.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }

    // ---- controls

    private fun player(key: String?): Player? =
        if (!listening) null else if (key == null) current.value.main?.let { m -> players.firstOrNull { it.key == m.key } } else players.firstOrNull { it.key == key }

    private fun audio() = app.getSystemService(AudioManager::class.java)

    private fun musicActive(): Boolean = runCatching { audio()?.isMusicActive == true }.getOrDefault(false)

    /** A press of a media key, which Android gives to whichever player is in front: the control that needs no access. */
    private fun mediaKey(code: Int) {
        val am = audio() ?: return
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    /**
     * The media key [code] in a player's place, for the first player only ([key] null). A key names
     * one player: when that one has gone or doesn't answer, nothing else is touched, where a media
     * key would go to whichever player is in front ("Other players": that player and no other).
     */
    private fun keyInstead(key: String?, code: Int) { if (key == null) mediaKey(code) }

    /** Plays or pauses the player [key], or the first one; for the first one without access (or a player), the media key. */
    fun playPause(key: String? = null) {
        val p = player(key) ?: return keyInstead(key, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        runCatching { if (p.wasPlaying) p.controller.transportControls.pause() else p.controller.transportControls.play() }
            .onFailure { keyInstead(key, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
    }

    fun next(key: String? = null) {
        val p = player(key) ?: return keyInstead(key, KeyEvent.KEYCODE_MEDIA_NEXT)
        runCatching { p.controller.transportControls.skipToNext() }.onFailure { keyInstead(key, KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    fun previous(key: String? = null) {
        val p = player(key) ?: return keyInstead(key, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        runCatching { p.controller.transportControls.skipToPrevious() }.onFailure { keyInstead(key, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    }

    /** Moves the player [key] to [positionMs], where it can seek. */
    fun seekTo(key: String, positionMs: Long) {
        val p = player(key) ?: return
        runCatching { p.controller.transportControls.seekTo(positionMs.coerceAtLeast(0)) }
    }

    /** Opens the player's own window: what its session offers for that, else its launcher entry. False if neither worked. */
    @Suppress("DEPRECATION") // the mode's newer name exists from Android 16 on; this one works from 14
    fun open(key: String? = null): Boolean {
        val p = player(key) ?: return false
        val own = runCatching {
            val pending = p.controller.sessionActivity ?: return@runCatching false
            // Android starts another app's window from here only if asked to in so many words.
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pending.send(app, 0, null, null, null, null, options.toBundle())
            true
        }.getOrDefault(false)
        if (own) return true
        val launch = runCatching { app.packageManager.getLaunchIntentForPackage(p.controller.packageName) }.getOrNull() ?: return false
        return Env.launch(launch, quiet = true)
    }

    /** The wheel over the item: media volume, one step a notch, with Android's own volume panel as the feedback. */
    fun volume(steps: Int) {
        if (steps == 0) return
        runCatching {
            audio()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    // ---- tests on a device (debug builds' adb hook)

    /** Shows [playing] instead of the real thing, until called with null. */
    fun stage(playing: Playing?) {
        staged = playing
        publish()
        Ticker.refresh()
    }

    /** One line about the source for the log: how sessions are read, how many players, and how many notifications the listener ever got. No titles. */
    fun debugLine(): String {
        val how = when {
            staged != null -> "staged"
            !accessOn -> "none"
            listening && askedToBind -> "bound"
            listening -> "direct"
            refused -> "refused"
            else -> "starting"
        }
        return "live=$started access=$accessOn sessions=$how players=${players.size} audible=$audible listener=${MediaAccess.connected} received=${MediaAccess.received.get()}"
    }
}

package com.meridian11.books.bentobar.items

import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Kept
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.InUseRules.Click
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * Camera and mic: shows while another app uses a camera or records from the microphone, for how long, and whether the
 * microphone is muted; its menu can mute the microphone for every app. From Android's own callbacks (a camera that is
 * unavailable is in use; a recording that is not silenced is the microphone in use), which need no permission. BentoBar's own
 * Mirror is no call ([MirrorOpen]).
 *
 * The mute is BentoBar's while it holds it ([MuteKeeper]), and it gives it back at the moments [settle] names: the item
 * leaving the layout, the service ending, a start that finds no such item placed. The bar hiding is none of them, so a mute
 * outlives a full-screen call. A rebind of the service ends it too (it can't be told from the service ending). A mute that is
 * not BentoBar's is shown and left alone. The camera can't be switched off by an app: the menu leads to Android's privacy
 * settings for that.
 */
object InUseItem : ItemType("inuse", R.string.item_inuse_title, Sym.VIDEOCAM, R.string.item_inuse_desc) {
    override val group = ItemGroup.SYSTEM
    override val canBeActive = true
    override val addsWhenActive = true
    override val liveSource = true
    override val trigger = Trigger(R.string.trigger_inuse, R.string.trigger_inuse_short)

    /** The time since it came on, in the bar. */
    internal val time = Opt.bool("time", true)
    /** A click mutes and unmutes instead of opening the menu; offered only while [InUseRules.OFFER_MUTE]. */
    internal val byClick = Opt.bool("click", false)

    /** Android's microphone switch for every app, behind an interface a test fakes. */
    interface MicMute { var muted: Boolean }

    /** BentoBar's mute and whether it holds one. A `var` so a test can give it a fake microphone and do disk work on the spot. */
    internal var mute = MuteKeeper(AndroidMic, { Kept.own(type) }, { Background.executor(type).execute(it) })

    /** What was sampled last. Written on the main thread. */
    @Volatile private var current = InUse()
    /** Debug builds: what `stage` put in place of [current], and a device that doesn't take the mute. */
    @Volatile private var staged: InUse? = null
    @Volatile private var stagedRefused = false
    private const val STAGED_MS = 247_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** The cameras Android says are unavailable, by id. Main thread. */
    private val busy = HashSet<String>()
    /** The recordings that are not silenced. Main thread. */
    private var recordings = 0
    private var live = false
    private var watchingCameras = false
    private var watchingRecordings = false
    /**
     * The sample right after [onLive]: the cameras' first word is on its way (Android posts it), so stepping now would read
     * "none in use", reset the time and pop the item out of the bar for a second. That one sample keeps the last look.
     */
    private var awaitingCameras = false

    private fun cameras(): CameraManager? = Env.app.getSystemService(CameraManager::class.java)
    private fun audio(): AudioManager? = Env.app.getSystemService(AudioManager::class.java)

    // Made when first needed, not with the object: the tests that load every type have no Android behind them.
    private val cameraWatch by lazy {
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) = heard(camera = true) { busy -= cameraId }
            // An external camera unplugged is "unavailable" too, and gone from the list: that is no camera in use.
            override fun onCameraUnavailable(cameraId: String) = heard(camera = true) { if (present(cameraId)) busy += cameraId else busy -= cameraId }
        }
    }
    private val recordingWatch by lazy {
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = heard(camera = false) { recordings = counted(configs) }
        }
    }

    private fun present(cameraId: String): Boolean = runCatching { cameras()?.cameraIdList?.contains(cameraId) }.getOrNull() ?: true
    private fun counted(configs: List<AudioRecordingConfiguration>) = configs.count { !it.isClientSilenced }

    /** Android said something ([camera]: of the cameras): taken at once, not at the next second. A callback from Android never throws. */
    private fun heard(camera: Boolean, change: () -> Unit) {
        try {
            change()
            if (camera) awaitingCameras = false
            if (live && !awaitingCameras) { step(Now.elapsed()); Ticker.refresh(type) }
        } catch (e: Exception) {
            Log.w(Env.TAG, "inuse: a callback failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onLive() {
        live = true
        awaitingCameras = true
        val cm = cameras()
        val am = audio()
        if (!watchingCameras && cm != null) watchingCameras = runCatching { cm.registerAvailabilityCallback(cameraWatch, main) }.isSuccess
        if (!watchingRecordings && am != null) watchingRecordings = runCatching { am.registerAudioRecordingCallback(recordingWatch, main) }.isSuccess
        recordings = runCatching { am?.activeRecordingConfigurations?.let(::counted) }.getOrNull() ?: 0
    }

    /** Lets go of both callbacks. Never unmutes: the bar hiding is no reason to (see [settle]). */
    override fun onIdle() {
        live = false
        if (watchingCameras) { runCatching { cameras()?.unregisterAvailabilityCallback(cameraWatch) }; watchingCameras = false }
        if (watchingRecordings) { runCatching { audio()?.unregisterAudioRecordingCallback(recordingWatch) }; watchingRecordings = false }
        busy.clear()
        recordings = 0
    }

    override fun sample(now: Long) {
        if (awaitingCameras) { awaitingCameras = false; return }
        step(now)
    }

    private fun step(now: Long) {
        val muted = mute.muted
        mute.seen(muted)
        current = InUseRules.step(current.copy(ours = mute.ours), busy.size, MirrorOpen.now, recordings, muted, now)
    }

    /** What the bar and the menu show: the staged look in a debug build, else the last sampled one. */
    internal fun shown(): InUse = staged ?: current

    /** Whether the device took a mute when asked: a staged "no" in a debug build, else what the last try found. */
    internal fun muteWorks(): Boolean = if (staged != null) !stagedRefused else mute.works

    @StringRes internal fun wordsRes(s: InUse): Int = when {
        s.muted && s.camera -> R.string.inuse_desc_camera_muted
        s.muted -> R.string.inuse_desc_muted
        s.camera && s.mic -> R.string.inuse_desc_both
        s.camera -> R.string.inuse_desc_camera
        s.mic -> R.string.inuse_desc_mic
        else -> R.string.inuse_desc_none
    }

    override fun state(item: ItemConfig): ItemState {
        val s = shown()
        val text = InUseRules.text(s, Now.elapsed(), time.of(item))
        val on = InUseRules.active(s)
        return ItemState(icon = InUseRules.icon(s), filled = on, text = text, desc = Env.str(wordsRes(s)), active = on,
            tone = InUseRules.tone(s), widthKey = if (text != null) "time" else null)
    }

    /** A call is the first thing the Live activity shows. Never the staged look: that stays this type's own. */
    override fun liveNow(now: Long, items: List<ItemConfig>): LiveNow? {
        val s = current
        if (!s.inUse) return null
        val showTime = items.firstOrNull()?.let(time::of) ?: true
        return LiveNow(LiveRank.CALL, InUseRules.icon(s), InUseRules.text(s, now, showTime) ?: title, Env.str(wordsRes(s)), InUseRules.tone(s), s.since)
    }

    override fun onClick(item: ItemConfig): Boolean = when (InUseRules.click(current, byClick.of(item))) {
        Click.MUTE -> { muteNow(); true }
        Click.UNMUTE -> { unmuteNow(); true }
        Click.MENU -> false
    }

    /** Mutes the microphone for every app, as BentoBar's own mute; the bar and an open menu follow at once. Main thread. */
    internal fun muteNow() { mute.mute(); afterChange() }

    /** Gives a mute of BentoBar's back. Main thread. */
    internal fun unmuteNow() { mute.unmute(); afterChange() }

    private fun afterChange() {
        if (live) step(Now.elapsed())
        Ticker.refresh(type)
    }

    override fun settle(why: Settle) {
        val placed = Store.config.value.items.any { it.type == type }
        mute.settle(why, placed)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host -> InUseMenu(host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        // The default is stored as no key, so turning a switch back leaves the item as fresh as it came.
        SwitchRow(stringResource(R.string.inuse_option_time), time.of(item)) { set(item.with(time.key, if (it) null else "false")) }
        if (InUseRules.OFFER_MUTE) SwitchRow(stringResource(R.string.inuse_option_click), byClick.of(item),
            help = stringResource(R.string.inuse_option_click_help)) { set(item.with(byClick.key, if (it) "true" else null)) }
    }

    override val stages = listOf("stage camera", "stage mic", "stage both", "stage muted", "stage nomute")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=${staged != null} camera=${current.camera} mic=${current.mic} muted=${current.muted} ours=${mute.ours} muteWorks=${mute.works}"
        "stage" -> {
            // Four minutes in, whatever the clock says, so a staged look reads the same on every day.
            val since = Now.elapsed() - STAGED_MS
            val s = when (args.getOrNull(1)) {
                "camera" -> InUse(camera = true, since = since)
                "mic", "nomute" -> InUse(mic = true, since = since)
                "both" -> InUse(camera = true, mic = true, since = since)
                "muted" -> InUse(mic = true, since = since, muted = true, ours = true)
                else -> null
            }
            if (s == null) null else {
                staged = s
                stagedRefused = args[1] == "nomute"
                Ticker.refresh(type)
                "staged ${args[1]}"
            }
        }
        "off" -> {
            staged = null
            stagedRefused = false
            Ticker.refresh(type)
            "staging off"
        }
        else -> null
    }
}

/**
 * BentoBar's mute of the microphone: whether it holds one ([ours], kept in [Kept] so a restart still knows), whether the
 * device took it ([works]: Android's flag read back unchanged means it did not), and giving it back. Only a mute BentoBar made
 * is ever undone. [background] runs the disk work (writing and removing the note); the decision itself is taken on the
 * caller's thread, so a service that is ending has unmuted before it returns.
 */
internal class MuteKeeper(
    private val mic: InUseItem.MicMute,
    private val kept: () -> Kept,
    private val background: (Runnable) -> Unit,
) {
    @Volatile var ours = false
        private set
    @Volatile var works = true
        private set

    /** Android's word: whether the microphone is muted for every app. */
    val muted: Boolean get() = mic.muted

    /**
     * Mutes the microphone as BentoBar's own. False if it was muted already (that mute is not ours to take) or the device did
     * not take it ([works] is then false, and the menu says so instead of offering it).
     */
    fun mute(): Boolean {
        if (mic.muted) return false
        mic.muted = true
        if (!mic.muted) { works = false; return false }
        works = true
        ours = true
        background(Runnable { runCatching { kept().write(OURS, "1", System.currentTimeMillis()) } })
        return true
    }

    /** Gives BentoBar's mute back; nothing for a mute that is not ours. */
    fun unmute() {
        if (!ours) return
        mic.muted = false
        forget()
    }

    /** The microphone was seen [muted] or not: a mute of ours that someone undid is ours no more. */
    fun seen(muted: Boolean) {
        if (ours && !muted) forget()
    }

    /**
     * [why] came ([ItemType.settle]); [placed]: the layout has an item of the type. A mute of ours is given back when
     * [InUseRules.undoMute] says so; one somebody undid is forgotten; one that stays is known as ours again after a restart.
     * The note on disk is asked whether it exists, which reads nothing ([Kept.has]).
     */
    fun settle(why: Settle, placed: Boolean) {
        val wasOurs = ours || runCatching { kept().has(OURS) }.getOrDefault(false)
        if (!wasOurs) return
        val muted = mic.muted
        when {
            InUseRules.undoMute(InUse(muted = muted, ours = true), why, placed) -> { mic.muted = false; forget() }
            !muted -> forget()
            else -> ours = true
        }
    }

    private fun forget() {
        ours = false
        background(Runnable { runCatching { kept().remove(OURS) } })
    }

    private companion object {
        const val OURS = "ours"
    }
}

/** The microphone switch on Android: `AudioManager`'s, for every app. Never throws; what can't be read counts as not muted. */
private object AndroidMic : InUseItem.MicMute {
    private fun am(): AudioManager? = Env.app.getSystemService(AudioManager::class.java)
    override var muted: Boolean
        get() = runCatching { am()?.isMicrophoneMute }.getOrNull() == true
        set(value) {
            try {
                am()?.isMicrophoneMute = value
            } catch (e: Exception) {
                Log.w(Env.TAG, "can't switch the microphone's mute: ${e.javaClass.simpleName}")
            }
        }
}

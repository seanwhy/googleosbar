package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym

/**
 * What the Camera and mic item knows at one moment: whether another app uses a camera or records from the microphone,
 * since when ([since], in the clock `sample` gets; 0 while neither is in use), whether the microphone is muted for every
 * app, and whether that mute is BentoBar's own ([ours]: the only kind it ever undoes).
 */
data class InUse(val camera: Boolean = false, val mic: Boolean = false, val since: Long = 0L, val muted: Boolean = false, val ours: Boolean = false) {
    val inUse: Boolean get() = camera || mic
}

/** The Camera and mic item's rules, pure. Android's side (the two callbacks, the microphone switch) is in [InUseItem]. */
object InUseRules {
    /**
     * Whether the item offers to mute the microphone at all. Android's flag is read back after it is set, but that proves only
     * that the flag is set, not that the microphone a call uses is silent: if the device check finds a recording that still
     * hears the user, this goes to false and the item only shows what is in use (no mute row, no mute by a click).
     */
    const val OFFER_MUTE = true

    /**
     * The next look from Android's word: [camerasBusy] cameras unavailable (one of them the Mirror's own while [ownCamera]:
     * the user looking at themselves is no call), [recordings] recordings that are not silenced, the microphone [muted].
     * A call counts from when the first of camera and microphone came on, and a mute is ours only while it lasts.
     */
    fun step(last: InUse, camerasBusy: Int, ownCamera: Boolean, recordings: Int, muted: Boolean, now: Long): InUse {
        val camera = camerasBusy - (if (ownCamera) 1 else 0) > 0
        val mic = recordings > 0
        val since = when {
            !camera && !mic -> 0L
            last.inUse -> last.since
            else -> now
        }
        return InUse(camera, mic, since, muted, ours = last.ours && muted)
    }

    /** Has something to say: something is in use, or the microphone is muted (a user who is muted must be able to see it). */
    fun active(s: InUse): Boolean = s.inUse || s.muted

    fun icon(s: InUse): String = when {
        s.muted -> Sym.MIC_OFF
        s.camera -> Sym.VIDEOCAM
        s.mic -> Sym.MIC
        else -> Sym.VIDEOCAM
    }

    fun tone(s: InUse): Tone = when {
        s.muted -> Tone.WARN
        s.inUse -> Tone.ACCENT
        else -> Tone.NORMAL
    }

    /** How long it has been in use, "4:07" or "1:02:11", when the item shows the time ([time]) and something is in use. */
    fun text(s: InUse, now: Long, time: Boolean): String? =
        if (!time || !s.inUse) null else Fmt.clock((now - s.since).coerceAtLeast(0), elapsed = true)

    enum class Click { MUTE, UNMUTE, MENU }

    /**
     * What a click on the item does. The menu, unless muting by a click is on ([byClick]) and offered at all ([offer]): then a
     * click undoes a mute of ours, and mutes a microphone in use that is not muted. Somebody else's mute is left alone.
     */
    fun click(s: InUse, byClick: Boolean, offer: Boolean = OFFER_MUTE): Click = when {
        !byClick || !offer -> Click.MENU
        s.ours && s.muted -> Click.UNMUTE
        s.mic && !s.muted -> Click.MUTE
        else -> Click.MENU
    }

    /**
     * Whether BentoBar gives its mute back at [why] (null: the bar merely hid, which is no such moment): when the item leaves
     * the layout, when the service ends, and at a start that finds a mute of ours with no such item [placed] to show it. Never
     * a mute that is not ours: unmuting somebody unasked in a call is the worse fault.
     */
    fun undoMute(s: InUse, why: Settle?, placed: Boolean): Boolean = s.ours && s.muted && when (why) {
        Settle.LEFT_LAYOUT, Settle.SERVICE_ENDING -> true
        Settle.SERVICE_STARTED -> !placed
        null -> false
    }
}
